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
 * 操作审计日志：登录/签发/吊销/导出等管理操作留痕。
 */
@Data
@TableName("ssl_audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 操作人
     */
    private String username;

    /**
     * 操作归属租户（全局操作记 default；租户维度过滤由后续波次接入）
     */
    private String tenantKey;

    /**
     * 操作：LOGIN/LOGOUT/CA_INIT/CERT_ISSUE/CERT_CSR_ISSUE/CERT_REVOKE/CERT_DOWNLOAD/CRL_DOWNLOAD
     */
    private String action;

    /**
     * 操作对象（证书ID/序列号等）
     */
    private String target;

    /**
     * 操作详情
     */
    private String detail;

    /**
     * 哈希链前驱记录ID（首条为NULL；对账接口按 id 序核验存在性与签名衔接）
     */
    private Long prevId;

    /**
     * 操作来源IP
     */
    private String ip;

    /**
     * 审计签名（SM3withSM2 hex，canonical=username|action|target|detail|ip|createdAt[|prevSign]；GM/T 0034 §10.1/§10.5 防篡改）
     */
    private String sign;

    /**
     * 哈希链：上一条审计记录的签名值（链式防篡改增强，行业实践）
     */
    private String prevSign;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
