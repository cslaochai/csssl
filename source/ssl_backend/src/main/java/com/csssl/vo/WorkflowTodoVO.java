package com.csssl.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 统一待办条目（把 RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建四条流抽象成同一条记录）。
 *
 * <p>这是「审批中心聚合页」的数据契约：四条流字段各异（申请单号/变更单号/证书主体…），
 * 但对用户而言待办只有一种形态——「什么单、到哪一步、现在归谁管、我该点什么」。
 * 故统一收敛为：单据标识 + 当前环节 + 当前处理人 + 一个可跳转的动作。</p>
 */
@Data
public class WorkflowTodoVO {

    /**
     * 业务类型：CERT_APPLICATION / CERT_OP / COMPANY_OP / SUB_CA_REBUILD
     */
    private String bizType;

    /**
     * 业务类型中文名（流程名）
     */
    private String bizTypeName;

    /**
     * 单据 id（雪花 ID，超 JS Number 安全整数范围——必须序列化为字符串，
     * 否则浏览器端 JSON.parse 丢精度，前端拿着错误 ID 调接口必然 404）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bizId;

    /**
     * 单号（RA 申请单号 / 操作单号 / 变更单号 / 重建单号）
     */
    private String bizNo;

    /**
     * 摘要（申请人 / 证书主体 / 变更事由…，一行讲清这是什么单）
     */
    private String title;

    /**
     * 归属公司（统一社会信用代码）
     */
    private String companyUscc;

    /**
     * 归属公司名称
     */
    private String companyName;

    /**
     * 当前状态编码
     */
    private String status;

    /**
     * 当前状态中文
     */
    private String statusText;

    /**
     * 当前停留环节名（如「审核审批」「保管人审批（3-of-5）」）
     */
    private String stepName;

    /**
     * 当前处理人（具体人优先；无具体人时退化为角色池标签）
     */
    private String nextHandler;

    /**
     * 发起人姓名
     */
    private String requestedByName;

    /**
     * 发起时间
     */
    private String requestedAt;

    /**
     * 动作类型：APPROVE-审批 / VOTE-投票 / ISSUE-签发 / EXECUTE-执行重建 / VIEW-仅查看
     */
    private String actionType;

    /**
     * 动作按钮文案
     */
    private String actionLabel;

    /**
     * 前端跳转路径（带 query，直达该单详情）
     */
    private String route;
}
