package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公司变更审批单（新建/暂停/恢复/关闭/重置接入密钥 全量走单，GM/T 0034 §10.1g 分权制衡）：
 * 发起（持 company:op:apply）→ 审批（持 company:op:approve，不得与发起人同一人）→ 批准即执行。
 * 直接执行通道已拆除，含系统管理员在内一律只能发起或查看。
 * 接入密钥明文不落库：执行结果只记 App ID 与版本，发起人凭一次性回执从密钥保险库领取。
 *
 * <p>人员一律以 {@code ssl_sys_user.id} + 真实姓名快照落库（保管人名单同序存 id 列与姓名列）：
 * 登录账号是凭证，不进业务表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_company_op_ticket")
public class CompanyOpTicket extends AuditColumns {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 变更单号（TO + 时间戳 + 随机）
     */
    private String opNo;

    /**
     * 变更类型：CREATE-新建 SUSPEND-暂停 RESUME-恢复 CLOSE-关闭 RESET_CREDENTIAL-重置接入密钥
     */
    private String opType;

    /**
     * 目标公司（统一社会信用代码；CREATE 时为拟登记代码）
     */
    private String companyUscc;

    /**
     * 公司名称（CREATE 参数快照，审批展示）
     */
    private String companyName;

    /**
     * 证书主体O（CREATE 参数快照）
     */
    private String subjectO;

    /**
     * 算法（CREATE 参数快照）
     */
    private String algorithm;

    /**
     * 联系人（CREATE 参数快照）
     */
    private String contact;

    /**
     * 联系方式（CREATE 参数快照）
     */
    private String contactPhone;

    /**
     * 说明（CREATE 参数快照）
     */
    private String description;

    /**
     * 变更事由（审批依据，必填）
     */
    private String reason;

    /**
     * 状态：PENDING-待审批 EXECUTED-已执行 REJECTED-已驳回 CANCELLED-已撤销
     */
    private String status;

    /**
     * 发起人ID（ssl_sys_user.id，用于审批人不得同人校验）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long requestedByUserId;

    /**
     * 发起人真实姓名（写入时快照，不回查用户表；展示用）
     */
    private String requestedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestedAt;

    /**
     * 审批人ID（ssl_sys_user.id）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewedByUserId;

    /**
     * 审批人真实姓名（写入时快照，不回查用户表；展示用）
     */
    private String reviewedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;

    /**
     * 被指派审批人ID（ssl_sys_user.id；提交时按角色 + 积压最少自动分派，审批准入闸门）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assigneeUserId;

    /**
     * 被指派审批人真实姓名（写入时快照，不回查用户表；展示用）
     */
    private String assigneeRealName;

    /**
     * 审批意见（驳回必填）
     */
    private String reviewComment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedAt;

    /**
     * 执行结果：接入 App ID（CREATE / RESET_CREDENTIAL）
     */
    private String resultAppId;

    /**
     * 执行结果：接入密钥版本
     */
    private Integer resultSecretVersion;

    /**
     * 接入凭证一次性回执领取时间（非空=已领取，不可再查看）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime receiptTakenAt;

    /**
     * 新密钥保管人ID名单（仅 REASSIGN_HOLDERS；逗号分隔 ssl_sys_user.id，执行时重新绑定）
     */
    private String newHolderUserIds;

    /**
     * 新密钥保管人姓名名单（仅 REASSIGN_HOLDERS；与 newHolderUserIds 同序，展示用快照）
     */
    private String newHolderNames;

    /**
     * 原密钥保管人ID快照（仅 REASSIGN_HOLDERS；逗号分隔，提交时锁定为投票人）
     */
    private String voterUserIds;

    /**
     * 原密钥保管人姓名名单（仅 REASSIGN_HOLDERS；与 voterUserIds 同序，展示用快照）
     */
    private String voterNames;

    /**
     * 当前同意数（REASSIGN_HOLDERS 专用）
     */
    private Integer agreeCount;

    /**
     * 当前驳回数（REASSIGN_HOLDERS 专用）
     */
    private Integer rejectCount;

    /**
     * 执行所需同意数（REASSIGN_HOLDERS 专用：5 名原保管人中须超过 3 人同意，即至少 4 人）
     */
    private Integer voteRequired;

    /** 非表字段：当前登录人是否在投票人名单且尚未投票（前端控制「同意/驳回」按钮显隐） */
    @TableField(exist = false)
    private Boolean myVotable;

    /** 非表字段：当前登录人是否已投票（含投票结果展示） */
    @TableField(exist = false)
    private Boolean myVoted;

    /** 非表字段：投票明细（详情展示） */
    @TableField(exist = false)
    private java.util.List<CompanyOpVote> votes;

    /** 非表字段：当前登录人可否撤回本单（待审批且发起人是我；与 cancel 同一口径，前端只看它） */
    @TableField(exist = false)
    private Boolean revocable;

    /**
     * 非表字段：当前登录人可否审批本单（待审批且被指派给我，或指派人已停用/未指派且我在公司审批员池）
     */
    @TableField(exist = false)
    private Boolean reviewable;

    /**
     * 非表字段：当前登录人可否催办本单（待审批且已指派审批人）
     */
    @TableField(exist = false)
    private Boolean urgeable;

    /** 非表字段：当前登录人可否领取接入凭证回执（已执行的新建/重置密钥单、发起人是我、回执未领；与 takeReceipt 同一口径） */
    @TableField(exist = false)
    private Boolean receiptClaimable;
}
