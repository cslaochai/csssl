package com.csssl.dto;

import lombok.Data;

/**
 * CSR 签发请求：上传 PKCS#10 证书请求，主体与公钥取自 CSR。
 */
@Data
public class CsrIssueRequest {

    /** PKCS#10 证书请求 PEM（BEGIN CERTIFICATE REQUEST） */
    private String csrPem;

    /** 证书类型：SERVER / CLIENT / SUB_CA，默认 SERVER */
    private String certType;

    /** 有效期（天），留空用默认值 */
    private Integer validityDays;

    /** SAN（逗号分隔）；CSR 自带 SAN 时优先用 CSR 的 */
    private String san;

    /** 用途说明 */
    private String description;
}
