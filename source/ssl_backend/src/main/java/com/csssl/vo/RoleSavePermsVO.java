package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 角色权限保存回执视图对象：角色编码 + 保存后的生效权限点（原 Map 两键结构）。
 */
@Data
public class RoleSavePermsVO {

    private String roleCode;
    private List<String> perms;
}
