package com.csssl.dto;

import lombok.Data;

/**
 * 公钥签发请求（RA 场景）：申请方自持私钥，仅提交 Subject DN 与公钥 PEM。
 */
@Data
public class PublicKeyIssueDTO {

    /**
     * RFC2253 格式主体 DN，如 "CN=张三,O=长沙市麓康医院,OU=门诊部"
     */
    private String subjectDn;

    /**
     * 公钥 PEM（SPKI，RSA2048 或 SM2 均可）
     */
    private String publicKeyPem;

    /**
     * 证书类型：SERVER / CLIENT（签名证书用 CLIENT）
     */
    private String certType;

    /**
     * 有效期（天），缺省用系统默认
     */
    private Integer validityDays;

    /**
     * SAN（逗号分隔，DNS名/IP）：RA 申请单携带时必须透传进证书，不得丢弃
     */
    private String san;

    /**
     * 证书模板ID（非空则以模板锁定算法/有效期/KeyUsage/EKU/策略OID）
     */
    private Long templateId;

    /**
     * 备注
     */
    private String description;
}
