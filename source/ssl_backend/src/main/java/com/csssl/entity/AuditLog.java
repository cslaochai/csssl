package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志：登录/签发/吊销/导出等管理操作留痕。
 */
@Data
@TableName("ssl_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 */
    private String username;

    /** 操作：LOGIN/LOGOUT/CA_INIT/CERT_ISSUE/CERT_CSR_ISSUE/CERT_REVOKE/CERT_DOWNLOAD/CRL_DOWNLOAD */
    private String action;

    /** 操作对象（证书ID/序列号等） */
    private String target;

    /** 操作详情 */
    private String detail;

    /** 操作来源IP */
    private String ip;

    /** 审计签名（SM3withSM2 hex，canonical=username|action|target|detail|ip|createdAt；GM/T 0034 §10.1/§10.5 防篡改） */
    private String sign;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
