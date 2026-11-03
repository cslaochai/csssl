package com.csssl.workflow;

import java.util.List;
import java.util.Map;

/**
 * 一条审批工作流的声明式定义：有序步骤 + 状态→步骤映射。
 * 由 {@link WorkflowRegistry} 装配，前端生成流程图、后端推导当前步/下一处理人。
 */
public class WorkflowDefinition {

    /** 业务类型编码（与 ssl_message.biz_type / 轨迹 bizType 对齐） */
    private String bizType;
    /** 流程名 */
    private String name;
    /** 流程说明 */
    private String description;
    /** 有序步骤 */
    private List<WorkflowStep> steps;
    /** 单据状态 → 当前步骤编码 */
    private Map<String, String> statusToStep;

    public WorkflowStep stepByCode(String code) {
        if (code == null || steps == null) {
            return null;
        }
        return steps.stream().filter(s -> s.getCode().equals(code)).findFirst().orElse(null);
    }

    public WorkflowStep currentStep(String status) {
        if (status == null || statusToStep == null) {
            return null;
        }
        return stepByCode(statusToStep.get(status));
    }

    public WorkflowStep nextStep(String currentCode) {
        if (currentCode == null || steps == null) {
            return null;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getCode().equals(currentCode)) {
                return i + 1 < steps.size() ? steps.get(i + 1) : null;
            }
        }
        return null;
    }

    public int indexOfStep(String code) {
        if (code == null || steps == null) {
            return -1;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getCode().equals(code)) {
                return i;
            }
        }
        return -1;
    }

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<WorkflowStep> getSteps() {
        return steps;
    }

    public void setSteps(List<WorkflowStep> steps) {
        this.steps = steps;
    }

    public Map<String, String> getStatusToStep() {
        return statusToStep;
    }

    public void setStatusToStep(Map<String, String> statusToStep) {
        this.statusToStep = statusToStep;
    }
}
