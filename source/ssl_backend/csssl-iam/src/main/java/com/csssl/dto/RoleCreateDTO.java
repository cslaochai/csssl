package com.csssl.dto;

import lombok.Data;

/**
 * 创建自定义角色请求（EJBCA 式：预置角色为默认，支持后台自建角色）。
 * 角色编码须为大写字母/数字/下划线、以字母开头、≤32 位，且不与预置角色冲突、库内唯一。
 */
@Data
public class RoleCreateDTO {

    /**
     * 角色编码（大写字母/数字/下划线，以字母开头，≤32位）
     */
    private String roleCode;

    /**
     * 角色名称（展示用）
     */
    private String roleName;

    /**
     * 职责说明
     */
    private String roleDesc;

    /**
     * 排序号
     */
    private String sortOrder;
}
