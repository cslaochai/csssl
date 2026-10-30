package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 证书操作单分页查询（status/opType 精确、keyword 命中 主体CN/序列号/单号、tenantKey 域过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CertOpPageQueryDTO extends PageParam {

    /**
     * 状态精确过滤（PENDING/EXECUTED/REJECTED/CANCELLED）
     */
    private String status;

    /**
     * 操作类型过滤（REVOKE/SUSPEND/UNSUSPEND）
     */
    private String opType;

    /**
     * 关键字（命中 主体CN/序列号/单号）
     */
    private String keyword;

    /**
     * 租户数据域过滤
     */
    private String tenantKey;
}
