package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 外部系统撤销申请：申请单号与原因全部放在请求体内，URL 不暴露参数。
 */
@Data
public class ApiCancelDTO {

    @NotBlank(message = "applyNo 不能为空")
    private String applyNo;

    @Size(max = 200, message = "撤销原因不超过200字符")
    private String reason;
}
