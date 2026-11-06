package com.csssl.dto;

import lombok.Data;

/** 工作流审批人候选查询（替换 WorkflowEngineController 的 candidates @RequestParam flowCode）。 */
@Data
public class WorkflowCandidatesDTO {
    private String flowCode;
}
