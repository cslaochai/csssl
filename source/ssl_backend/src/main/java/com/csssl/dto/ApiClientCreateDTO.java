package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登记外部 API 客户端请求（仅系统管理员）。
 */
@Data
public class ApiClientCreateDTO {

    @NotBlank(message = "必须指定客户端绑定的公司")
    private String companyUscc;

    @NotBlank(message = "客户端名称不能为空")
    private String clientName;

    /**
     * 系统名称（= 证书主体 O）：必须已在「系统管理」中登记，公司内唯一
     */
    @NotBlank(message = "必须指定所属系统")
    private String systemName;

    /**
     * 逗号分隔 IP/CIDR 白名单（可选）
     */
    private String ipAllowlist;

    /**
     * mTLS 客户端证书 SHA-256 指纹（可选；配置后请求必须携带该证书）
     */
    private String clientCertSha256;
}
