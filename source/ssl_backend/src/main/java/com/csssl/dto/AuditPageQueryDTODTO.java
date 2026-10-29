package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计日志分页查询（action 精确、orgKey 域过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditPageQueryDTODTO extends PageParam {

    /** 操作类型精确过滤 */
    private String action;

    /** 机构数据域过滤 */
    private String orgKey;
}
