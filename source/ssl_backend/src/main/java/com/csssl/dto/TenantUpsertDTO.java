package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建租户请求（登记即建独立根CA；tenantKey 格式与主体O占用由服务端校验）。
 */
@Data
public class TenantUpsertDTO {

    /**
     * 租户标识（小写字母数字，参与密钥库路径）
     */
    @NotBlank(message = "租户标识不能为空")
    private String tenantKey;

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
     * 联系人
     */
    private String contact;

    /**
     * 说明
     */
    private String description;
}
