package com.csssl.dto;

import lombok.Data;

/** 工作流轨迹查询（替换 WorkflowEngineController history 的 @PathVariable String businessId）。 */
@Data
public class WorkflowHistoryDTO {
    private String businessId;
}
