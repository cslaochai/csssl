package com.csssl.vo;

import lombok.Data;

/**
 * 登录口令加密公钥下发视图对象：SM2 公钥（hex）+ 本轮密钥标识。
 */
@Data
public class PublicKeyVO {

    /**
     * 公钥缓存标识（前端加密时回传，供服务端取对应私钥）
     */
    private String keyId;

    /**
     * SM2 未压缩公钥点十六进制（04 前缀）
     */
    private String publicKey;
}
