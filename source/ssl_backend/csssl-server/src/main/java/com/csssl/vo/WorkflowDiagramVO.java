package com.csssl.vo;

import java.util.List;

/**
 * 由工作流定义 + 单据当前状态推导出的流程图（前端直接渲染）。
 */
public class WorkflowDiagramVO {

    private String bizType;
    private String name;
    private List<WorkflowStepStateVO> steps;
    /** 当前停留步骤编码（终态/未知为 null） */
    private String currentStepCode;
    /** 下一步处理人提示（具体人或角色池标签） */
    private String nextApproverLabel;

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<WorkflowStepStateVO> getSteps() {
        return steps;
    }

    public void setSteps(List<WorkflowStepStateVO> steps) {
        this.steps = steps;
    }

    public String getCurrentStepCode() {
        return currentStepCode;
    }

    public void setCurrentStepCode(String currentStepCode) {
        this.currentStepCode = currentStepCode;
    }

    public String getNextApproverLabel() {
        return nextApproverLabel;
    }

    public void setNextApproverLabel(String nextApproverLabel) {
        this.nextApproverLabel = nextApproverLabel;
    }
}
