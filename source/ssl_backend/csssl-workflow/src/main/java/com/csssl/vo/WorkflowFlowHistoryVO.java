package com.csssl.vo;

import lombok.Data;

/**
 * 流程轨迹条目（自研工作流引擎的历史任务，供待办弹窗画完整生命周期）。
 *
 * <p>操作人只出真实姓名快照（{@code flow_his_task.collaborator}）：引擎历史表里的
 * {@code approver} 存的是登录账号，属凭证，禁止随业务出参下发。</p>
 */
@Data
public class WorkflowFlowHistoryVO {

    /**
     * 节点编码
     */
    private String nodeCode;

    /**
     * 节点名称（环节名，如「审核审批」「执行签发」）
     */
    private String nodeName;

    /**
     * 办理人真实姓名
     */
    private String handlerName;

    /**
     * 结果：PASS-通过 REJECT-驳回 CANCELED-作废
     */
    private String result;

    /**
     * 结果中文
     */
    private String resultText;

    /**
     * 办理意见
     */
    private String message;

    /**
     * 办理时间（yyyy-MM-dd HH:mm:ss）
     */
    private String time;
}
