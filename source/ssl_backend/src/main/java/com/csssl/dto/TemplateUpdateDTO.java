package com.csssl.dto;

import lombok.Data;

/**
 * 更新证书模板请求（部分更新：只改传入的字段；编码与类型不可改）。
 */
@Data
public class TemplateUpdateDTO {

    /**
     * 模板名称（null=不改）
     */
    private String templateName;

    /**
     * 算法（null=不改）
     */
    private String algorithm;

    /**
     * 有效期天数（null=不改）
     */
    private Integer validityDays;

    /**
     * KeyUsage（null=不改）
     */
    private String keyUsage;

    /**
     * EKU（null=不改；""=清除）
     */
    private String extKeyUsage;

    /**
     * 策略 OID（null=不改；""=清除）
     */
    private String policyOid;

    /**
     * 说明（null=不改；""=清除）
     */
    private String description;
}
