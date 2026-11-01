package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户变更审批单（新建/暂停/恢复/关闭/重置接入密钥 全量走单，GM/T 0034 §10.1g 分权制衡）：
 * 发起（持 tenant:op:apply）→ 审批（持 tenant:op:approve，不得与发起人同一人）→ 批准即执行。
 * 直接执行通道已拆除，含系统管理员在内一律只能发起或查看。
 * 接入密钥明文不落库：执行结果只记 App ID 与版本，发起人凭一次性回执从密钥保险库领取。
 */
@Data
@TableName("ssl_tenant_op_ticket")
public class CaTenantOpTicket {

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
     * 目标租户（统一社会信用代码；CREATE 时为拟登记代码）
     */
    private String tenantUscc;

    /**
     * 租户名称（CREATE 参数快照，审批展示）
     */
    private String tenantName;

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
     * 发起人（登录账号，用于审批人不得同人校验）
     */
    private String requestedBy;

    /**
     * 发起人真实姓名（展示用）
     */
    private String requestedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestedAt;

    /**
     * 审批人（登录账号）
     */
    private String reviewedBy;

    private String reviewedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;

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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
