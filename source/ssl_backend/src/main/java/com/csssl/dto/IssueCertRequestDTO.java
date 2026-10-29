package com.csssl.dto;

import lombok.Data;

/**
 * 证书签发请求（管理后台在线签发，内部生成密钥对）。
 */
@Data
public class IssueCertRequestDTO {

    /** 主体CN（必填，颁发给哪个子系统/主机/服务） */
    private String subjectCn;

    /** 证书类型：SERVER-服务端 CLIENT-客户端 SUB_CA-二级CA */
    private String certType;

    /** 算法：RSA2048 / SM2（国密），默认 RSA2048 */
    private String algorithm;

    /** 有效期（天），留空用默认值 */
    private Integer validityDays;

    /** SAN（逗号分隔，DNS名/IP） */
    private String san;

    /** 证书模板ID（非空则类型/算法/有效期/KeyUsage/EKU/策略OID 全部以模板为准，GM/T 0034 §5.3.4） */
    private Long templateId;

    /** 用途说明（颁发给哪个子系统：cslk/csmig/csmis等） */
    private String description;
}
