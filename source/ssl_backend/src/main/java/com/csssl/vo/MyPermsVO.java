package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前登录人角色与生效权限视图对象（登录后自刷新入口；前端据此重建侧栏与按钮）。
 */
@Data
public class MyPermsVO {

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 角色状态：1 启用 / 0 停用
     */
    private Integer roleStatus;

    /**
     * 生效权限点（菜单 + 按钮）
     */
    private List<String> perms;
}
