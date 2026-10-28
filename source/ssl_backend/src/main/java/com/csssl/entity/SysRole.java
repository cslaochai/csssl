package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理角色档案（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 *
 * <p><b>六类法定角色固化</b>：角色编码由标准职责划分确定，编码不可改、角色不可增删
 * （{@code builtin}=1 内置），可维护的只有展示名称、职责说明、排序与启停；
 * 角色的可授权权限点集合由 {@link com.csssl.util.PermissionCatalog} 的法定上限约束，
 * 配置只能在上限内收窄，不能越界扩权（防止"一人兼签发与审核"破坏分权制衡）。</p>
 */
@Data
@TableName("ssl_sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码（ADMIN/OPERATOR/APPROVER/AUDITOR/SECURITY_ADMIN/KEY_ADMIN，唯一且不可改） */
    private String roleCode;

    /** 角色名称（可维护，用于页面展示与账号归属说明） */
    private String roleName;

    /** 职责说明（可维护，对应 GM/T 0034 分权职责） */
    private String roleDesc;

    /** 排序号（升序展示，小在前） */
    private Integer sortOrder;

    /** 状态：1-启用 0-停用（停用后该角色账号不可登录） */
    private Integer status;

    /** 1-法定内置角色（不可删） */
    private Integer builtin;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
