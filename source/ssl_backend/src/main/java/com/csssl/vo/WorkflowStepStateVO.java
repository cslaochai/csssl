package com.csssl.vo;

/**
 * 流程图单步的展示态（含当前所处状态）。
 */
public class WorkflowStepStateVO {

    private String code;
    private String name;
    private String actorLabel;
    private String assignStrategy;
    private boolean terminal;
    /** DONE=已流转过 / CURRENT=当前停留 / PENDING=尚未到达 */
    private String state;

    public WorkflowStepStateVO() {
    }

    public WorkflowStepStateVO(String code, String name, String actorLabel, String assignStrategy,
                              boolean terminal, String state) {
        this.code = code;
        this.name = name;
        this.actorLabel = actorLabel;
        this.assignStrategy = assignStrategy;
        this.terminal = terminal;
        this.state = state;
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

    public String getAssignStrategy() {
        return assignStrategy;
    }

    public void setAssignStrategy(String assignStrategy) {
        this.assignStrategy = assignStrategy;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
