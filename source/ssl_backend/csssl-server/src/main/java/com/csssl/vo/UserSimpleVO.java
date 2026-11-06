package com.csssl.vo;

import lombok.Data;

/**
 * 用户简易项（保管人下拉用）：仅暴露必要字段（不暴露口令/审计相关字段）。
 */
@Data
public class UserSimpleVO {

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
    private String roleCode;

    /**
     * 归属公司；NULL=全局管理员
     */
    private String companyUscc;

    /**
     * 1-启用 0-禁用
     */
    private Integer status;
}
