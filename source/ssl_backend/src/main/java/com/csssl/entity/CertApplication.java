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
 * 证书申请单（RA 用户注册流程，GM/T 0034 §5.2.2）：
 * 提交（录入员）→ 审核批准/驳回（审核员，不得与录入员同一人）→ 签发（录入员）。
 */
@Data
@TableName("ssl_cert_application")
public class CertApplication {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 受理租户（签发落到该租户根域）；default=院内默认域
     */
    private String tenantKey;

    /**
     * 申请单号（RA20261027xxx）
     */
    private String applyNo;

    /**
     * 申请人类别（证书主体类型）：INSTITUTION-单位 / PERSON-个人；与租户租户域无关
     */
    private String applicantType;

    /**
     * 申请人/租户名称
     */
    private String applicantName;

    /**
     * 证书主体DN（RFC2253）
     */
    private String subjectDn;

    /**
     * 证书模板ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long templateId;

    /**
     * 批准时固化的模板策略JSON（算法/有效期/KeyUsage/EKU/策略OID），签发按快照执行
     */
    private String templateSnapshot;

    /**
     * SAN列表（DNS/IP，逗号分隔）
     */
    private String san;

    /**
     * 申请方公钥PEM（自持私钥场景；空则由CA在线生成）
     */
    private String publicKeyPem;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 状态：PENDING-待审核 APPROVED-已批准 REJECTED-已驳回 ISSUED-已签发 CANCELLED-已撤销
     */
    private String status;

    /**
     * 提交人（录入员，与审核人不得同一人）；存登录账号，用于身份校验
     */
    private String appliedBy;

    /**
     * 提交人真实姓名（展示用；历史数据可能为 NULL）
     */
    private String appliedByName;

    /**
     * 提交时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime appliedAt;

    /**
     * 审核人；存登录账号，用于身份校验
     */
    private String reviewedBy;

    /**
     * 审核人真实姓名（展示用；历史数据可能为 NULL）
     */
    private String reviewedByName;

    /**
     * 审核时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;

    /**
     * 审核意见
     */
    private String reviewComment;

    /**
     * 签发证书ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long issuedCertId;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
