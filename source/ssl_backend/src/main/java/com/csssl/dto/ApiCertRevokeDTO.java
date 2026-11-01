package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 外部系统吊销证书：序列号与原因放在请求体内，URL 不暴露参数。
 */
@Data
public class ApiCertRevokeDTO {

    @NotBlank(message = "serialHex 不能为空")
    private String serialHex;

    @Size(max = 200, message = "吊销原因不超过200字符")
    private String reason;
}
