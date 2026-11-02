package com.csssl.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 报文签名工具：SHA-256 摘要与 HMAC-SHA256，统一输出小写 hex。
 *
 * <p>与 {@code CaApiAuthFilter} 内的验签实现同口径（canonical 用换行拼接，签名取 hex），
 * 供出站方向的推送签名复用。后续如需收敛为单一实现，可让 filter 也改为委托本类。
 */
public final class SignUtil {

    private SignUtil() {
    }

    /** SHA-256 摘要，返回小写 hex（64 字符） */
    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** HMAC-SHA256，返回小写 hex（64 字符） */
    public static String hmacHex(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 常量时间比较，避免逐字节比对泄露时序信息 */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0x0F];
            out[i * 2 + 1] = digits[bytes[i] & 0x0F];
        }
        return new String(out);
    }
}
