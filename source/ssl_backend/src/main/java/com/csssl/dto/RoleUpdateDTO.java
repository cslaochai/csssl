package com.csssl.dto;

import lombok.Data;

/**
 * 更新角色档案请求（部分更新：只改传入的字段；sortOrder/status 为字符串口径，服务端解析）。
 */
@Data
public class RoleUpdateDTO {

    /** 角色名称（null=不改） */
    private String roleName;

    /** 职责说明（null=不改；""=清空） */
    private String roleDesc;

    /** 排序号（数字字符串，null=不改） */
    private String sortOrder;

    /** 状态："1" 启用 / "0" 停用（null=不改） */
    private String status;
}
