package com.csssl.dto;

import lombok.Data;

/** 工作流流程图查询（替换 WorkflowController 的三个 @RequestParam）。 */
@Data
public class WorkflowDiagramDTO {
    private String bizType;
    private String status;
    private String assigneeRealName;
}
