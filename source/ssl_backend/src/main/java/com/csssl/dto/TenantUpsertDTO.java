package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建租户请求（登记即建独立根CA）。租户标识由统一社会信用代码派生，不接收人工填写；
 * 主体O占用由服务端校验。
 */
@Data
public class TenantUpsertDTO {

    /**
     * 统一社会信用代码（18位，唯一；租户标识取它的小写形式）
     */
    @NotBlank(message = "统一社会信用代码必填")
    @Pattern(regexp = "(?i)^[0-9A-HJ-NPQRTUWXY]{18}$",
            message = "统一社会信用代码须为18位（字母不含 I/O/S/V/Z）")
    private String uscc;

    /**
     * 租户名称
     */
    @NotBlank(message = "租户名称必填")
    @Size(max = 64, message = "租户名称必填且不超过64字符")
    private String tenantName;

    /**
     * 证书主体O
     */
    @NotBlank(message = "证书主体O必填")
    @Size(max = 128, message = "证书主体O必填且不超过128字符")
    private String subjectO;

    /**
     * 根CA算法（缺省取全局配置 csssl.root-ca.algorithm）
     */
    private String algorithm;

    /**
     * 联系人姓名
     */
    @Size(max = 64, message = "联系人不超过64字符")
    private String contact;

    /**
     * 联系人手机号（11位，可留空；列表/详情展示时做掩码）
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "联系方式须为11位手机号")
    private String contactPhone;

    /**
     * 说明
     */
    private String description;
}
