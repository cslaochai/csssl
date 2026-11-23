package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 二级CA 重建申请单视图（含审批人名单、逐人审批记录与当前登录人待办标记）。
 */
@Data
public class CaSubCaTicketVO {

    /**
     * 申请单ID（雪花 ID，超 JS Number 安全整数范围——必须序列化为字符串，
     * 否则浏览器端 JSON.parse 丢精度，前端拿着错误 ID 调审批/执行必然 404）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String opNo;
    private String companyUscc;
    private String companyName;
    private String reason;
    /**
     * 发起人真实姓名（登录账号不下发前端，按钮显隐看 revocable / executableByMe）
     */
    private String requestedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestedAt;

    private int threshold;
    private String status;
    private int approvedCount;
    private int rejectedCount;

    /**
     * 审批阶段已收集的保管人份额数（按份额编号去重）：达到阈值即可直接执行重建，无需再手动粘贴份额。
     */
    private int collectedShareCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedAt;

    /**
     * 审批人名单（5 名密钥保管人）
     */
    private List<CaSubCaHolderVO> holders;

    /**
     * 逐人审批记录
     */
    private List<CaSubCaApprovalVO> approvals;

    /**
     * 当前登录人是否待审批（是保管人且尚未投票）
     */
    private boolean myPendingDecision;

    /**
     * 当前登录人已做出的决定（APPROVED / REJECTED / null）
     */
    private String myDecision;

    /**
     * 当前登录人（发起人本人）可否撤回本单：仅 PENDING；与 cancel 同一口径
     */
    private boolean revocable;

    /**
     * 当前登录人（发起人本人）可否执行重建：仅 APPROVED；用于审批凑齐门限后自动弹执行框
     */
    private boolean executableByMe;
}
