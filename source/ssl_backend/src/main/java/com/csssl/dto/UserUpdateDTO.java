package com.csssl.dto;

import lombok.Data;

/**
 * 更新账号请求（部分更新：只改传入的字段；status 为字符串口径 "0"/"1"）。
 */
@Data
public class UserUpdateDTO {

    /** 姓名（null=不改） */
    private String realName;

    /** 角色编码（null=不改） */
    private String roleCode;

    /** 状态："1" 启用 / "0" 停用（null=不改） */
    private String status;

    /** 重置口令（null=不重置） */
    private String password;
}
