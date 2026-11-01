package com.csssl.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 外部系统撤销申请（可选原因，写入审计）。
 */
@Data
public class ApiCancelDTO {

    @Size(max = 200, message = "撤销原因不超过200字符")
    private String reason;
}
