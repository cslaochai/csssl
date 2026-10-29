package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 角色权限详情视图对象：已配置权限点 + 全量权限目录（角色配置页权限树回显）。
 */
@Data
public class RolePermsVO {

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 职责说明
     */
    private String roleDesc;

    /**
     * 内置角色：1 是 / 0 否
     */
    private Integer builtin;

    /**
     * 已配置权限点
     */
    private List<String> configured;

    /**
     * 全量权限目录（含是否在该角色法定边界内）
     */
    private List<PermItemVO> catalog;
}
