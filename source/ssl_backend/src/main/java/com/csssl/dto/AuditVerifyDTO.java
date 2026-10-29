package com.csssl.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 审计记录验签请求。
 */
@Data
public class AuditVerifyDTO {

    /** 待验签的审计日志ID */
    @NotNull(message = "参数 id 不能为空")
    private Long id;
}
