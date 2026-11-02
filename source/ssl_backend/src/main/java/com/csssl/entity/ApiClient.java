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
 * 外部系统 API 客户端（HIS 等）：公司由服务端绑定，请求不携带公司标识；
 * app_id 与 secret 均为随机不可猜串，secret 密文存密钥保险库（api-clients/{appId}），本表不落明文。
 */
@Data
@TableName("ssl_api_client")
public class ApiClient {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 随机客户端标识（app_ + 32 位十六进制）
     */
    private String appId;

    /**
     * 绑定公司（请求数据域唯一来源，不信任调用方传参）
     */
    private String companyUscc;

    /**
     * 客户端名称（审计展示）
     */
    private String clientName;

    /**
     * 逗号分隔 IP/CIDR 白名单（空=不限）
     */
    private String ipAllowlist;

    /**
     * mTLS 客户端证书 SHA-256 指纹（空=不校验）
     */
    private String clientCertSha256;

    /**
     * secret 版本（轮换 +1）
     */
    private Integer secretVersion;

    /**
     * 1-启用 0-停用
     */
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUsedAt;

    private String createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rotatedAt;
}
