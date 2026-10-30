package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 证书模板分页查询（keyword 命中 编码/名称、tenantKey 域过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TemplatePageQueryDTO extends PageParam {

    /**
     * 关键字（命中 模板编码/名称）
     */
    private String keyword;

    /**
     * 租户数据域过滤
     */
    private String tenantKey;
}
