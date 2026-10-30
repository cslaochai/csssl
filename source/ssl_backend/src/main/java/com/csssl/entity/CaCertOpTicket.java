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
 * 证书操作审批单（吊销/挂起/解除挂起/更新/重签双人复核，GM/T 0034 §10.1g 分权制衡）：
 * 发起（持 cert:revoke / cert:suspend / cert:renew / cert:rekey 权限者）
 * → 审批（持 cert:op:approve 权限者，不得与发起人同一人）
 * → 批准即执行（同事务内落 CRL/OCSP 状态或重签新证）。直接执行通道已拆除，含系统管理员在内一律走单。
 */
@Data
@TableName("ssl_cert_op_ticket")
public class CaCertOpTicket {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 归属租户（与目标证书行一致）
     */
    private String tenantKey;

    /**
     * 操作单号（CO20261030xxx）
     */
    private String opNo;

    /**
     * 操作类型：REVOKE-吊销 SUSPEND-挂起 UNSUSPEND-解除挂起 RENEW-证书更新 REKEY-密钥更新
     */
    private String opType;

    /**
     * 目标证书ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long certId;

    /**
     * 目标证书序列号（发起时快照，展示/审计用）
     */
    private String certSerialHex;

    /**
     * 目标证书主体CN（发起时快照）
     */
    private String certSubjectCn;

    /**
     * 操作原因（RFC 5280 原因码，如 KEY_COMPROMISE；解除挂起/更新/重签可为空）
     */
    private String reason;

    /**
     * 新有效期天数（RENEW/REKEY 操作单专用，批准后按此天数重签）
     */
    private Integer validityDays;

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

    /**
     * 发起时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestedAt;

    /**
     * 审批人（登录账号）
     */
    private String reviewedBy;

    /**
     * 审批人真实姓名（展示用）
     */
    private String reviewedByName;

    /**
     * 审批时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;

    /**
     * 审批意见（驳回必填）
     */
    private String reviewComment;

    /**
     * 执行时间（批准即执行，与审批时间同事务）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedAt;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
