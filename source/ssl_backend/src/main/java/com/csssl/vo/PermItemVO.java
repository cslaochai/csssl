package com.csssl.vo;

import lombok.Data;

/**
 * 权限目录项视图对象：角色配置页权限树渲染用（allowed=是否在该角色法定边界内）。
 */
@Data
public class PermItemVO {

    private String code;
    private String name;
    private String type;
    private String group;
    private String note;
    private boolean allowed;
}
