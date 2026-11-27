package com.csssl.dto;

import lombok.Data;

/**
 * 证书链在线校验请求（业务系统调 POST /ca/public/verify 用）。
 * 公司域 / 系统名均走 body，路径上不带路径参数。
 */
@Data
public class VerifyQueryDTO {

    /**
     * 公司域 USCC；留空回落 default 主域
     */
    private String companyUscc;

    /**
     * 系统名；留空则不限定系统归属（仅按公司域链校验）
     */
    private String systemName;

    /**
     * 待校验证书 PEM
     */
    private String certPem;

    /**
     * 用途：TLS-服务端/双向TLS SIGN-签名验证；留空则只做链与时效校验
     */
    private String purpose;
}
