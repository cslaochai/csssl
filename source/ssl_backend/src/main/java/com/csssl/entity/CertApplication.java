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
 * 证书申请单（RA 用户注册流程，GM/T 0034 §5.2.2）：
 * 提交（录入员，必须指派审批人）→ 审核批准/驳回（被指派的审核员，不得与录入员同一人）→ 签发（录入员）。
 *
 * <p>人员一律以 {@code ssl_sys_user.id} + 真实姓名快照落库：登录账号是凭证，不进业务表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_cert_application")
public class CertApplication extends AuditColumns {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 受理公司（签发落到该公司根域）；default=院内默认域
     */
    private String companyUscc;

    /**
     * 申请单号（RA20261027xxx）
     */
    private String applyNo;

    /**
     * 申请人类别（证书主体类型）：INSTITUTION-单位 / PERSON-个人；与公司公司域无关
     */
    private String applicantType;

    /**
     * 申请人/公司名称
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
     * 外部系统标识（API 客户端 appId；管理端录入为空）
     */
    private String externalSystem;

    /**
     * 外部系统申请唯一ID（同公司同客户端内幂等）
     */
    private String externalRequestId;

    /**
     * 幂等请求体指纹（SHA-256；同ID不同体判冲突）
     */
    private String externalRequestHash;

    /**
     * 外部员工主键（对账用）
     */
    private String externalEmployeeId;

    /**
     * 外部门员工号
     */
    private String externalEmployeeCode;

    /**
     * 外部科室名称（展示用，不进 DN）
     */
    private String externalDeptName;

    /**
     * 员工编号（主体DN 的 OU，WEB/API 通道必填）：同一公司内唯一识别，
     * 与 HIS 员工编号对齐；重名医生靠它区分
     */
    private String employeeNo;

    /**
     * 来源渠道：WEB-管理端录入 API-外部系统直连
     */
    private String channel;

    /**
     * 状态：PENDING-待审核 APPROVED-已批准 REJECTED-已驳回 ISSUED-已签发 CANCELLED-已撤销
     */
    private String status;

    /**
     * 提交人ID（ssl_sys_user.id，与审核人不得同一人）：用于身份校验与数据域收口；
     * 对外 API 通道无人可指，为 NULL（客户端名称落在 appliedByName）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long appliedByUserId;

    /**
     * 提交人真实姓名（写入时快照，不回查用户表；展示用）
     */
    private String appliedByName;

    /**
     * 提交时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime appliedAt;

    /**
     * 审核人ID（ssl_sys_user.id）：用于身份校验
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewedByUserId;

    /**
     * 审核人真实姓名（写入时快照，不回查用户表；展示用）
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
     * 提交时指定的具体审批人ID（ssl_sys_user.id；审批准入闸门：只有该用户可批准/驳回，
     * 同时用于列表可见范围与催办收件人）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assigneeUserId;

    /**
     * 指定审批人真实姓名（写入时快照，不回查用户表；展示用）
     */
    private String assigneeRealName;

    /**
     * 当前登录人可否审核本单（列表查询时按登录人现算，不落库；口径见 CertApplicationService#checkAssignee）
     */
    @TableField(exist = false)
    private Boolean reviewable;

    /**
     * 本单可否催办（待审且已指派到具体审批人；列表查询时现算，不落库）
     */
    @TableField(exist = false)
    private Boolean urgeable;

    /**
     * 签发证书ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long issuedCertId;
}
