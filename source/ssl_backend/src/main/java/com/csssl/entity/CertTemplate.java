package com.csssl.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书模板：固化证书类型/算法/有效期/KeyUsage/EKU/策略OID（GM/T 0034 §5.3.4 证书策略管理，
 * GM/T 0015 证书格式）。签发必须按模板进行，杜绝手工拼扩展项。
 */
@Data
@TableName("ssl_cert_template")
public class CertTemplate {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 归属机构；NULL=全局模板（所有机构可用，兼容内置模板） */
    private String orgKey;

    /** 模板编码（唯一）：SERVER_COMM-机构通信 / DOCTOR_SIGN-医生签名 / SUB_CA-二级CA */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 证书类型：SERVER/CLIENT/SUB_CA */
    private String certType;

    /** 算法：RSA2048 / SM2 */
    private String algorithm;

    /** 默认有效期（天） */
    private Integer validityDays;

    /** KeyUsage（逗号分隔）：digitalSignature,keyEncipherment,keyCertSign,cRLSign */
    private String keyUsage;

    /** 扩展密钥用途 EKU：serverAuth / clientAuth（SUB_CA 置空） */
    private String extKeyUsage;

    /** 证书策略 OID（如 1.2.156.112562.2.2.15，SM2 签名证书策略） */
    private String policyOid;

    /** 1-内置模板（不可删除） 0-自定义 */
    private Integer builtin;

    /** 模板说明 */
    private String description;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
