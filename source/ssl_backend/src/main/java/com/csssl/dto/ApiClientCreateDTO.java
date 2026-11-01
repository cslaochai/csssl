package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登记外部 API 客户端请求（仅系统管理员）。
 */
@Data
public class ApiClientCreateDTO {

    @NotBlank(message = "必须指定客户端绑定的租户")
    private String tenantKey;

    @NotBlank(message = "客户端名称不能为空")
    private String clientName;

    /**
     * 逗号分隔 IP/CIDR 白名单（可选）
     */
    private String ipAllowlist;

    /**
     * mTLS 客户端证书 SHA-256 指纹（可选；配置后请求必须携带该证书）
     */
    private String clientCertSha256;
}
