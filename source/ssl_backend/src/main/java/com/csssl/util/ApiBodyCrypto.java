package com.csssl.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 对外 API 业务报文整包加密（AES-256-GCM）。
 *
 * <p>密钥不单独发放：由客户端 secret 派生——SHA-256(secret + "|csssl-api-body-enc-v1")，
 * 因此一对 App ID/secret 同时承担验签与报文加密，轮换 secret 即同时换掉两者。</p>
 *
 * <p>信封：请求体整体替换为 {"encData":"base64(IV || 密文 || GCM tag)"}，
 * IV 每次随机 12 字节，tag 128 位。HMAC 签名仍按实际发送的密文信封计算，
 * 幂等指纹则取解密后的明文（见 CaApiAuthFilter）。</p>
 */
public final class ApiBodyCrypto {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String KDF_LABEL = "|csssl-api-body-enc-v1";

    private ApiBodyCrypto() {
    }

    /**
     * 解密信封中的 encData，返回 UTF-8 明文
     *
     * @throws IllegalArgumentException 密钥/IV/密文/tag 任一环节失败
     */
    public static String decrypt(String secret, String encData) {
        try {
            byte[] raw = Base64.getDecoder().decode(encData);
            if (raw.length <= IV_LEN) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            byte[] cipherText = new byte[raw.length - IV_LEN];
            System.arraycopy(raw, IV_LEN, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keyOf(secret), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体解密失败", e);
        }
    }

    private static SecretKeySpec keyOf(String secret) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] key = md.digest((secret + KDF_LABEL).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
