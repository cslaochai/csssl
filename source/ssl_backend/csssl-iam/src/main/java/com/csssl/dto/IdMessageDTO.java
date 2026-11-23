package com.csssl.dto;

import lombok.Data;

/** 通用：ID + message（WorkflowEngineController 的 approve/reject/withdraw）。 */
@Data
public class IdMessageDTO {
    private Long id;
    private String message;
}
