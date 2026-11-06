package com.csssl.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;

/**
 * 统一待办条目（把 RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建四条流抽象成同一条记录）。
 *
 * <p>这是「我的待办」审批工作台的数据契约：四类单据字段各异（申请单号/操作类型/证书主体…），
 * 但工作台只有一种形态——「什么单、到哪一步、现在归谁管、我能点哪些按钮」。
 * 故除通用骨架外，每类单据渲染一行表格 + 打开详情弹窗所需的业务列都平铺在这里，
 * 前端不再逐行回查明细接口；{@code actions} 由服务端按写接口的同一口径算出，
 * 前端不得自己拼权限。</p>
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
     * 当前处理人姓名（流程当前环节已派到人时取流程侧，否则回落单据上的指派姓名列；无人认领为 null）
     */
    private String assigneeRealName;

    /**
     * 发起人姓名
     */
    private String requestedByName;

    /**
     * 发起时间
     */
    private String requestedAt;

    // ==================== 分类型展示列（弹窗一行表格直出，前端不再回查明细接口） ====================

    /**
     * 操作/变更类型编码（CERT_OP、COMPANY_OP 有值，其余类型为 null）
     */
    private String opType;

    /**
     * 操作/变更类型中文
     */
    private String opTypeText;

    /**
     * 申请人/公司名称（仅 CERT_APPLICATION）
     */
    private String applicantName;

    /**
     * 证书主体 DN（仅 CERT_APPLICATION）
     */
    private String subjectDn;

    /**
     * 证书模板编码（仅 CERT_APPLICATION，由 template_id 关联 ssl_cert_template 取得）
     */
    private String templateCode;

    /**
     * 目标证书主体 CN（仅 CERT_OP）
     */
    private String certSubjectCn;

    /**
     * 目标证书序列号（仅 CERT_OP）
     */
    private String certSerial;

    /**
     * 来源渠道：API-外部系统直连 WEB-管理端录入（仅 CERT_APPLICATION，HIS 单需与手工单区分）
     */
    private String externalChannel;

    /**
     * 该单据是否挂了流程实例。历史存量单没有实例，弹窗据此解释「为什么看不到流程轨迹」。
     */
    private boolean flowActive;

    /**
     * 动作类型：APPROVE-审批 / VOTE-投票 / ISSUE-签发 / EXECUTE-执行重建 / VIEW-仅查看
     *
     * <p>仅保留给旧前端的主按钮文案；精确可执行动作看 {@link #actions}。</p>
     */
    private String actionType;

    /**
     * 动作按钮文案
     */
    private String actionLabel;

    /**
     * 当前登录人对该行可执行的动作编码集合（服务端按写接口同一口径逐行算出，
     * 空集合=只读）。取值见 WorkflowTodoService 的 ACTION_* 常量。
     */
    private List<String> actions;

    /**
     * 前端跳转路径（带 query，直达该单详情）
     */
    private String route;
}
