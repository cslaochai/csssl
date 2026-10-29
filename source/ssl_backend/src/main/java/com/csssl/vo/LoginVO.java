package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录成功视图对象：会话令牌 + 用户/角色/权限/机构数据域信息。
 */
@Data
public class LoginVO {

    /**
     * JWT 会话令牌
     */
    private String token;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 姓名
     */
    private String realName;

    /**
     * 角色编码
     */
    private String role;

    /**
     * 角色名称（以库中档案为准，前端顶栏展示）
     */
    private String roleName;

    /**
     * 生效权限点（菜单 + 按钮）：前端据此构建侧栏、守卫与按钮显隐
     */
    private List<String> perms;

    /**
     * 机构数据域：null=全局账号；前端据此锁定/展示机构切换
     */
    private String orgKey;

    /**
     * 平台主机构（各机构标识由管理员在机构管理维护；全局账号默认落在它上面）
     */
    private String primaryOrg;
}
