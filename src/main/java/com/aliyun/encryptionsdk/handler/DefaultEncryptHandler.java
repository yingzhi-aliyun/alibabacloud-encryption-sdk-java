/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aliyun.encryptionsdk.handler;

import com.aliyun.encryptionsdk.exception.AliyunException;
import com.aliyun.encryptionsdk.exception.CipherTextParseException;
import com.aliyun.encryptionsdk.model.*;
import com.aliyun.encryptionsdk.provider.BaseDataKeyProvider;
import com.aliyun.encryptionsdk.stream.CopyStreamUtil;
import com.aliyun.encryptionsdk.stream.CryptoInputStream;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * {@link EncryptHandler} 的默认实现
 */
public class DefaultEncryptHandler implements EncryptHandler {

    @Override
    public CipherMaterial encrypt(byte[] plaintext, EncryptionMaterial encryptionMaterial) {
        AlgorithmHandler handler = new AlgorithmHandler(encryptionMaterial.getAlgorithm(),
                encryptionMaterial.getPlaintextDataKey(), Cipher.ENCRYPT_MODE);
        CipherHeader cipherHeader = new CipherHeader(encryptionMaterial.getVersion(),
                encryptionMaterial.getEncryptedDataKeys(),
                encryptionMaterial.getEncryptionContext(), encryptionMaterial.getAlgorithm());
        cipherHeader.calculateHeaderAuthTag(handler);

        byte[] iv = randomIv(encryptionMaterial.getAlgorithm().getIvLen());
        byte[] context = null;
        if (cipherHeader.getAlgorithm().isWithAad()) {
            context = cipherHeader.getEncryptionContextBytes();
        }
        byte[] cipherResult = handler.cipherData(iv, context, plaintext, 0, plaintext.length);

        int tagLen = cipherHeader.getAlgorithm().getTagLen();
        byte[] cipherText = new byte[cipherResult.length - tagLen];
        byte[] authTag = new byte[tagLen];
        if (tagLen != 0) {
            System.arraycopy(cipherResult, 0, cipherText, 0, cipherResult.length - tagLen);
            System.arraycopy(cipherResult, cipherText.length, authTag, 0, tagLen);
        } else {
            cipherText = cipherResult;
        }
        CipherBody cipherBody = new CipherBody(iv, cipherText, authTag);
        return new CipherMaterial(cipherHeader, cipherBody);
    }

    private byte[] randomIv(int len) {
        byte[] iv = new byte[len];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);
        return iv;
    }

    @Override
    public byte[] decrypt(CipherMaterial cipherMaterial, DecryptionMaterial decryptionMaterial) {
        AlgorithmHandler handler = new AlgorithmHandler(decryptionMaterial.getAlgorithm(), decryptionMaterial.getPlaintextDataKey(), Cipher.DECRYPT_MODE);
        if(!verifyHeaderAuthTag(cipherMaterial.getCipherHeader(), handler)){
            throw new CipherTextParseException("header authTag verify failed");
        }


        CipherBody cipherBody = cipherMaterial.getCipherBody();
        byte[] cipherText = cipherBody.getCipherText();
        byte[] authTag = cipherBody.getAuthTag();
        if (authTag.length != cipherMaterial.getCipherHeader().getAlgorithm().getTagLen()) {
            throw new IllegalArgumentException("Invalid tag length: " + authTag.length);
        }
        byte[] result = new byte[cipherText.length + authTag.length];
        System.arraycopy(cipherText, 0, result, 0, cipherText.length);
        System.arraycopy(authTag, 0, result, cipherText.length, authTag.length);

        return handler.cipherData(cipherBody.getIv(), cipherMaterial.getCipherHeader().getEncryptionContextBytes(),
                result, 0, result.length);
    }

    @Override
    public CipherMaterial encryptStream(InputStream inputStream, OutputStream outputStream, BaseDataKeyProvider provider, EncryptionMaterial encryptionMaterial) {
        AlgorithmHandler handler = new AlgorithmHandler(encryptionMaterial.getAlgorithm(), encryptionMaterial.getPlaintextDataKey(), Cipher.ENCRYPT_MODE);
        CipherHeader cipherHeader = new CipherHeader(encryptionMaterial.getVersion(), encryptionMaterial.getEncryptedDataKeys(),
                encryptionMaterial.getEncryptionContext(), encryptionMaterial.getAlgorithm());
        cipherHeader.calculateHeaderAuthTag(handler);
        provider.writeCipherHeader(cipherHeader, outputStream);

        byte[] iv = randomIv(encryptionMaterial.getAlgorithm().getIvLen());
        writeIv(outputStream, iv);
        handler.cipherInit(iv);
        if (cipherHeader.getAlgorithm().isWithAad()) {
            handler.updateAAD(cipherHeader.getEncryptionContextBytes());
        }
        CryptoInputStream is = new CryptoInputStream(inputStream, handler, 4096);
        CopyStreamUtil.copyIsToOs(is, outputStream);

        CipherBody cipherBody = new CipherBody(iv, null);
        return new CipherMaterial(cipherHeader, cipherBody);
    }

    @Override
    public void decryptStream(InputStream inputStream, OutputStream outputStream, CipherMaterial cipherMaterial, DecryptionMaterial decryptionMaterial) {
        AlgorithmHandler handler = new AlgorithmHandler(decryptionMaterial.getAlgorithm(), decryptionMaterial.getPlaintextDataKey(), Cipher.DECRYPT_MODE);
        if(!cipherMaterial.getCipherHeader().verifyHeaderAuthTag(handler)){
            throw new CipherTextParseException("header authTag verify failed");
        }

        byte[] iv = cipherMaterial.getCipherBody().getIv();
        handler.cipherInit(iv);
        if (decryptionMaterial.getAlgorithm().isWithAad()) {
            handler.updateAAD(cipherMaterial.getCipherHeader().getEncryptionContextBytes());
        }
        CryptoInputStream is = new CryptoInputStream(inputStream, handler, 4096);
        CopyStreamUtil.copyIsToOs(is, outputStream);
    }

    private boolean verifyHeaderAuthTag(CipherHeader cipherHeader, AlgorithmHandler handler) {
        try {
            byte[] headerAuthTag = cipherHeader.getHeaderAuthTag();

            byte[] headerAuthTagCalc = handler.headerGcmEncrypt(cipherHeader.getHeaderIv(), cipherHeader.serializeAuthenticatedFields(), new byte[0], 0, 0);
            if(headerAuthTagCalc==null)
                return false;
            if (Arrays.equals(headerAuthTag, headerAuthTagCalc))
                return true;
            else
                return false;
        }catch(Exception e){
            return false;
        }
    }

    private void writeIv(OutputStream outputStream, byte[] iv) {
        try {
            outputStream.write(CopyStreamUtil.intToBytes(iv.length));
            outputStream.write(iv);
            outputStream.flush();
        } catch (IOException e) {
            throw new AliyunException(e);
        }
    }
}
