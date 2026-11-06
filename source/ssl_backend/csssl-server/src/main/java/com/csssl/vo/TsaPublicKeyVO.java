package com.csssl.vo;

import lombok.Data;

/**
 * TSA 公钥信息：PEM + SM3 指纹，供业务系统下载验签时间戳令牌。
 */
@Data
public class TsaPublicKeyVO {

    /**
     * 密钥算法
     */
    private String algorithm;

    /**
     * 令牌签名算法
     */
    private String signAlgo;

    /**
     * TSA 公钥 PEM
     */
    private String publicKeyPem;

    /**
     * 公钥 SM3 指纹（人工核对用）
     */
    private String fingerprint;

    /**
     * 服务说明
     */
    private String note;
}
