package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 换绑调用方 SM2 签名公钥请求。
 */
@Data
public class ApiClientKeyDTO {

    @NotNull(message = "id 不能为空")
    private Long id;

    /**
     * SM2 公钥（130 字符 hex、04 前缀）
     */
    @NotBlank(message = "公钥不能为空")
    private String signPublicKey;
}
