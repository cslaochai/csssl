package com.csssl.vo;

import lombok.Data;

/**
 * 角色档案更新回执视图对象。
 */
@Data
public class RoleDetailVO {

    /**
     * 角色编码
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 状态：1 启用 / 0 停用
     */
    private Integer status;
}
