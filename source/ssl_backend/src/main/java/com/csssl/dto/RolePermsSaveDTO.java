package com.csssl.dto;

import lombok.Data;

import java.util.List;

/**
 * 保存角色权限请求（菜单+按钮，整表替换）。
 */
@Data
public class RolePermsSaveDTO {

    /**
     * 目标角色编码（来自 body，替换原来的 @PathVariable）
     */
    private String roleCode;

    /**
     * 权限点列表（如 ["menu:certs","cert:revoke"]）
     */
    private List<String> perms;
}
