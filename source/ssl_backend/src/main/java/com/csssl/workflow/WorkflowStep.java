package com.csssl.workflow;

import java.util.List;

/**
 * 工作流的一个步骤（节点）。纯声明式配置，由 {@link WorkflowRegistry} 装配，
 * 前端据此生成流程图、后端据此推导「当前步骤 / 下一处理人」。
 */
public class WorkflowStep {

    /** 步骤编码（与状态机/轨迹 step 对齐，如 SUBMIT/REVIEW/ISSUE） */
    private String code;
    /** 步骤展示名（如「审核审批」） */
    private String name;
    /** 处理人展示标签（如「审核员 / RA管理员」），用于流程图与下一审批人提示 */
    private String actorLabel;
    /** 可处理该步的角色编码（引擎分派/准入时使用，展示用 actorLabel） */
    private List<String> roleCodes;
    /** 分派策略 */
    private AssignStrategy assignStrategy;
    /** 是否为终态步骤 */
    private boolean terminal;

    public WorkflowStep() {
    }

    public WorkflowStep(String code, String name, String actorLabel, List<String> roleCodes,
                        AssignStrategy assignStrategy, boolean terminal) {
        this.code = code;
        this.name = name;
        this.actorLabel = actorLabel;
        this.roleCodes = roleCodes;
        this.assignStrategy = assignStrategy;
        this.terminal = terminal;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public List<String> getRoleCodes() {
        return roleCodes;
    }

    public void setRoleCodes(List<String> roleCodes) {
        this.roleCodes = roleCodes;
    }

    public AssignStrategy getAssignStrategy() {
        return assignStrategy;
    }

    public void setAssignStrategy(AssignStrategy assignStrategy) {
        this.assignStrategy = assignStrategy;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }
}
