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
 * 管理用户（对齐 cslk sys_user / csmig mig_sys_user 核心字段）。
 */
@Data
@TableName("ssl_sys_user")
public class SysUser {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 登录账号
     */
    private String username;

    /**
     * BCrypt 密码哈希
     */
    private String password;

    /**
     * 姓名
     */
    private String realName;

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 归属租户；NULL=全局管理员（跨租户，兼容存量账号）
     */
    private String tenantKey;

    /**
     * 1-启用 0-禁用
     */
    private Integer status;

    /**
     * 最近登录时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginTime;

    /**
     * 最近登录IP
     */
    private String lastLoginIp;

    /**
     * 连续登录失败次数（成功后清零）
     */
    private Integer failedAttempts;

    /**
     * 锁定截止时间（NULL=未锁定）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lockedUntil;

    /**
     * 会话版本号：口令/角色/状态变更时+1，JWT 携带比对，旧 token 即刻失效
     */
    private Integer tokenVersion;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
