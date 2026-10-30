package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户分页查询（keyword 命中 标识/名称/主体O、status 精确）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantPageQueryDTO extends PageParam {

    /**
     * 关键字（命中 租户标识/名称/主体O）
     */
    private String keyword;

    /**
     * 租户状态精确过滤（ACTIVE/SUSPENDED/CLOSED）
     */
    private String status;
}
