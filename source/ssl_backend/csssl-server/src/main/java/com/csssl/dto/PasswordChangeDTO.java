package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 自助改密请求：oldPassword/newPassword 均为 SM2 密文（与登录同通道）。
 */
@Data
public class PasswordChangeDTO {

    /**
     * 原口令（SM2 密文）
     */
    @NotBlank(message = "原口令不能为空")
    private String oldPassword;

    /**
     * 新口令（SM2 密文）
     */
    @NotBlank(message = "新口令不能为空")
    private String newPassword;
}
