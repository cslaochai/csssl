package com.csssl.dto;

import lombok.Data;

/**
 * 登录请求：用户名 + SM2 加密后的口令密文。
 */
@Data
public class LoginRequest {

    /** 登录账号 */
    private String username;

    /** 口令密文：前端 sm-crypto 加密后的十六进制（不带 04 前缀，C1C3C2 模式） */
    private String passwordCipher;
}
