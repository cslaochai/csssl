package com.csssl.util;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.SM4;

import java.nio.charset.StandardCharsets;

/**
 * SM4 国密对称加密工具：为 HIS 生态（csmig 前置机生产期 SM4 加密报文等）提供加解密与密钥生成。
 * 默认 ECB/PKCS5Padding（hutool SM4 默认模式），调用方负责密钥分发与保管。
 */
public final class Sm4Util {

    /** 生成 128 位 SM4 密钥，返回 32 位十六进制 */
    public static String generateKeyHex() {
        return HexUtil.encodeHexStr(SecureUtil.generateKey("SM4", 128).getEncoded());
    }

    /** SM4 加密（ECB/PKCS5Padding）→ 十六进制密文 */
    public static String encryptHex(String keyHex, String plain) {
        SM4 sm4 = new SM4(HexUtil.decodeHex(keyHex));
        return sm4.encryptHex(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** SM4 解密（ECB/PKCS5Padding）← 十六进制密文 */
    public static String decryptHex(String keyHex, String cipherHex) {
        SM4 sm4 = new SM4(HexUtil.decodeHex(keyHex));
        byte[] plain = sm4.decrypt(HexUtil.decodeHex(cipherHex));
        return new String(plain, StandardCharsets.UTF_8);
    }
}
