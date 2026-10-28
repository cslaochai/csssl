package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书申请单（RA 用户注册流程，GM/T 0034 §5.2.2）：
 * 提交（录入员）→ 审核批准/驳回（审核员，不得与录入员同一人）→ 签发（录入员）。
 */
@Data
@TableName("ssl_cert_application")
public class CertApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请单号（RA20261027xxx） */
    private String applyNo;

    /** 申请人类别：INSTITUTION-机构 / PERSON-个人 */
    private String applicantType;

    /** 申请人/机构名称 */
    private String applicantName;

    /** 证件号（个人身份证 / 机构统一社会信用代码） */
    private String idNo;

    /** 证书主体DN（RFC2253） */
    private String subjectDn;

    /** 证书模板ID */
    private Long templateId;

    /** 批准时固化的模板策略JSON（算法/有效期/KeyUsage/EKU/策略OID），签发按快照执行 */
    private String templateSnapshot;

    /** SAN列表（DNS/IP，逗号分隔） */
    private String san;

    /** 申请方公钥PEM（自持私钥场景；空则由CA在线生成） */
    private String publicKeyPem;

    /** 用途说明 */
    private String description;

    /** 状态：PENDING-待审核 APPROVED-已批准 REJECTED-已驳回 ISSUED-已签发 CANCELLED-已撤销 */
    private String status;

    /** 提交人（录入员，与审核人不得同一人） */
    private String appliedBy;

    /** 提交时间 */
    private LocalDateTime appliedAt;

    /** 审核人 */
    private String reviewedBy;

    /** 审核时间 */
    private LocalDateTime reviewedAt;

    /** 审核意见 */
    private String reviewComment;

    /** 签发证书ID */
    private Long issuedCertId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
