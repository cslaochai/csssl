package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建管理账号请求（tenantKey 空=全局账号；角色/租户越域由服务端强制）。
 */
@Data
public class UserCreateDTO {

    /**
     * 登录账号
     */
    @NotBlank(message = "账号不能为空")
    private String username;

    /**
     * 初始口令（明文，HTTPS 通道内传输，服务端 BCrypt 存密）
     */
    @NotBlank(message = "初始口令不能为空")
    private String password;

    /**
     * 姓名（缺省取账号）
     */
    private String realName;

    /**
     * 角色编码（缺省 OPERATOR）
     */
    private String roleCode;

    /**
     * 归属租户（空=全局账号）
     */
    private String tenantKey;
}
