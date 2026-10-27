package com.csssl.dto;

import lombok.Data;

/**
 * 证书链在线校验请求（业务系统调 /ca/public/verify 用）。
 */
@Data
public class VerifyRequest {

    /** 待校验证书 PEM */
    private String certPem;

    /** 用途：TLS-服务端/双向TLS SIGN-签名验证；留空则只做链与时效校验 */
    private String purpose;
}
