package com.csssl.workflow.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StartRequest {

    private String flowCode;

    private String businessId;

    /**
     * 提交时人工指定的首个审批节点办理人（单选）；为空则按节点权限标识自动解析全部候选人。
     * 只能从该节点权限标识筛选出的候选人里选，服务端会再校验一次。
     */
    private Long assigneeUserId;

    private Map<String, Object> variables;
}
