package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.csssl.enums.RoleCodeEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理角色档案（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 *
 * <p><b>预置角色固化 + 自定义角色可配（EJBCA 式）</b>：{@code builtin}=1 的预置角色编码由
 * {@link RoleCodeEnum} 枚举固化（不可改、不可删），可维护的只有展示名称、职责说明、
 * 排序与启停，其授权上限由 {@link com.csssl.util.PermissionCatalog} 约束、只能收窄不能越界
 * （防止"一人兼签发与审核"破坏分权制衡）；{@code builtin}=0 的自定义角色由后台自建、可增删，
 * 授权上限为全量权限目录，由管理员自行组合。</p>
 */
@Data
@TableName("ssl_sys_role")
public class SysRole {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 角色编码（预置角色见 RoleCode 枚举；自定义角色由后台自建；唯一且不可改）
     */
    private String roleCode;

    /**
     * 角色名称（可维护，用于页面展示与账号归属说明）
     */
    private String roleName;

    /**
     * 职责说明（可维护，对应 GM/T 0034 分权职责）
     */
    private String roleDesc;

    /**
     * 排序号（升序展示，小在前）
     */
    private Integer sortOrder;

    /**
     * 状态：1-启用 0-停用（停用后该角色账号不可登录）
     */
    private Integer status;

    /**
     * 1-法定内置预置角色（不可删）0-自定义角色（可增删）
     */
    private Integer builtin;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
