package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 机构分页查询（keyword 命中 标识/名称/主体O、status 精确）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrgPageQueryDTODTO extends PageParam {

    /** 关键字（命中 机构标识/名称/主体O） */
    private String keyword;

    /** 机构状态精确过滤（ACTIVE/SUSPENDED/CLOSED） */
    private String status;
}
