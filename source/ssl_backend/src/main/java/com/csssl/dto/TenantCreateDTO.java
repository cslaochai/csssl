package com.csssl.dto;

import lombok.Data;

/**
 * 新建租户请求（持 tenant:create，直接执行：建租户即建独立根CA+SUB_CA 并发放默认接入凭证，无需双人复核）。
 */
@Data
public class TenantCreateDTO {

    /**
     * 统一社会信用代码（GB 32100，18 位；即租户标识）
     */
    private String uscc;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 证书主体O（该租户全部证书使用）
     */
    private String subjectO;

    /**
     * 算法（可空，缺省取全局配置）
     */
    private String algorithm;

    /**
     * 联系人（可空）
     */
    private String contact;

    /**
     * 联系方式（可空，11位手机号）
     */
    private String contactPhone;

    /**
     * 说明（可空）
     */
    private String description;
}
