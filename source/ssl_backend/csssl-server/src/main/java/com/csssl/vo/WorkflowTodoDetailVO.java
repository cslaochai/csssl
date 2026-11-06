package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 待办详情弹窗载荷：一份「业务行 + 审批轨迹 + 流程轨迹」的完整上下文。
 *
 * <p>{@code detail} 与 {@code trail} 刻意用 Object / List&lt;?&gt; 而非四个类型各自的强类型字段：
 * 四条流的单据分别落在四张表、形状互不相同（{@code CertApplication} / {@code CaCertOpTicket} /
 * {@code CompanyOpTicket} / {@code CaSubCaTicketVO}），轨迹同理（{@code ssl_application_trail} 的
 * 按步留痕 vs SUB_CA 的逐人投票记录）。摊平成显式字段会把四类完全不同的列塞进同一个 VO，
 * 反而比前端按 {@code bizType} 分支渲染更难读。序列化安全由出参自身保证：
 * 各实体的雪花 id 早已挂 {@code ToStringSerializer}。</p>
 */
@Data
public class WorkflowTodoDetailVO {

    /**
     * 业务类型：CERT_APPLICATION / CERT_OP / COMPANY_OP / SUB_CA_REBUILD
     */
    private String bizType;

    /**
     * 业务类型中文名（流程名）
     */
    private String bizTypeName;

    /**
     * 待办行本身（当前环节、当前处理人、状态、以及服务端算好的 actions）：
     * 弹窗的标题栏与按钮区只认这一份口径
     */
    private WorkflowTodoVO row;

    /**
     * 完整业务行（类型随 bizType 而定）
     */
    private Object detail;

    /**
     * 审批轨迹：三条审批流为 ssl_application_trail 的按步留痕（时间正序），
     * SUB_CA 重建为保管人逐人投票记录
     */
    private List<?> trail;

    /**
     * 流程引擎轨迹（节点、办理人姓名、通过/驳回、意见、时间）；历史存量单无流程实例时为空
     */
    private List<WorkflowFlowHistoryVO> flowHistory;
}
