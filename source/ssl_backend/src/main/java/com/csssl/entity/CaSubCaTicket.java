package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 二级CA 重建申请单：动用根私钥的高敏操作，须该公司 5 名保管人中任意 3 人审批同意（3-of-5）。
 *
 * <p>状态机：PENDING → APPROVED / REJECTED / CANCELLED；APPROVED 且粘贴 ≥3 份额加载根私钥后执行 → EXECUTED。
 * 任一保管人驳回即 REJECTED；同意人数达阈值（默认 3）即 APPROVED。审批人限定为该公司保管人（见
 * {@link CaSubCaApproval}），避免非保管人越权放行。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_ca_subca_ticket")
public class CaSubCaTicket extends AuditColumns {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 申请单号（RK + 时间戳 + 随机）
     */
    private String opNo;

    /**
     * 目标公司（统一社会信用代码）
     */
    private String companyUscc;

    /**
     * 公司名称（快照展示）
     */
    private String companyName;

    /**
     * 重建事由（审批依据，必填）
     */
    private String reason;

    /**
     * 发起人ID（ssl_sys_user.id）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long requestedByUserId;

    /**
     * 发起人真实姓名（写入时快照，不回查用户表）
     */
    private String requestedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestedAt;

    /**
     * 审批放行阈值（3-of-5 中任意 3 人）
     */
    private int threshold;

    /**
     * 状态：PENDING / APPROVED / REJECTED / CANCELLED / EXECUTED
     */
    private String status;

    /**
     * 已同意人数
     */
    private int approvedCount;

    /**
     * 已驳回人数
     */
    private int rejectedCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedAt;
}
