package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 账号管理分页查询（keyword 见 service；roleCode 精确、tenantKey 域过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageQueryDTO extends PageParam {

    /**
     * 角色编码精确过滤
     */
    private String roleCode;

    /**
     * 租户数据域过滤
     */
    private String tenantKey;
}
