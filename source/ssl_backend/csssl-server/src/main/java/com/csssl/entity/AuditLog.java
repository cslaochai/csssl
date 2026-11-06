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
 *
 * <p>刻意不挂通用审计列（{@link AuditColumns}）：本表只追加不改写，且 {@code createdAt}
 * 参与防篡改签名，必须与列精度 DATETIME(6) 严格一致，不能由通用填充器代管。</p>
 */
@Data
@TableName("ssl_audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 操作人ID（ssl_sys_user.id；系统自动写入与对外 API 通道为 NULL）——账号是凭证，绝不入业务表
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 操作人真实姓名（展示口径，写入时快照，不回查用户表；API 通道存客户端名称）
     */
    private String realName;

    /**
     * 操作归属公司（全局操作记 default；公司维度过滤由后续波次接入）
     */
    private String companyUscc;

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
     * 审计签名（SM3withSM2 hex，canonical=user_id|action|target|detail|ip|createdAt[|prevSign]；GM/T 0034 §10.1/§10.5 防篡改）
     */
    private String sign;

    /**
     * 哈希链：上一条审计记录的签名值（链式防篡改增强，行业实践）
     */
    private String prevSign;

    /**
     * 防篡改签名口径版本：1-旧（canonical 含登录账号，账号列已删除故不可再逐行验签，只参与链衔接）；
     * 2-新（canonical 含 user_id）。DDL 默认 1，新写入必须显式置 2。
     */
    private Integer signVer;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
