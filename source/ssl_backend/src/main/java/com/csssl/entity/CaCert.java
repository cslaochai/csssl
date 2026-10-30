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
 * CA 证书总账：根CA与所有签发证书（含吊销信息）。私钥只落盘（store-dir），不进本表。
 */
@Data
@TableName("ssl_ca_cert")
public class CaCert {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 所属机构（独立根CA域）；default=院内默认域（存量单根）
     */
    private String orgKey;

    /**
     * 证书序列号（16进制，唯一）
     */
    private String serialHex;

    /**
     * 主体CN（颁发给谁）
     */
    private String subjectCn;

    /**
     * 证书类型：ROOT-根CA SUB_CA-二级CA SERVER-服务端 CLIENT-客户端
     */
    private String certType;

    /**
     * 算法：RSA2048 / SM2
     */
    private String algorithm;

    /**
     * 签发者CN
     */
    private String issuerCn;

    /**
     * SAN列表（DNS/IP，逗号分隔）
     */
    private String san;

    /**
     * 生效时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validFrom;

    /**
     * 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validTo;

    /**
     * 状态：VALID-有效 REVOKED-已吊销 EXPIRED-已过期
     */
    private String status;

    /**
     * 吊销原因
     */
    private String revokeReason;

    /**
     * 吊销时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime revokedAt;

    /**
     * 证书PEM（公钥证书，可入库）
     */
    private String certPem;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 签发所用证书模板ID（GM/T 0034 §5.3.4，可空=直签未走模板）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long templateId;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
