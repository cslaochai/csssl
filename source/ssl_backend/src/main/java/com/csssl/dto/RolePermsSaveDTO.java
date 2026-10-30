package com.csssl.dto;

import lombok.Data;

import java.util.List;

/**
 * 保存角色权限请求（菜单+按钮，整表替换）。
 */
@Data
public class RolePermsSaveDTO {

    /**
     * 权限点列表（如 ["menu:certs","cert:issue"]）
     */
    private List<String> perms;
}
