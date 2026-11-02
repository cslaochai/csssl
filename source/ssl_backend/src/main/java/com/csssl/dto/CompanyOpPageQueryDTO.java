package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公司变更单分页查询（status/opType 精确、keyword 命中 单号/信用代码/公司名称）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CompanyOpPageQueryDTO extends PageParam {

    /**
     * 状态精确过滤（PENDING/EXECUTED/REJECTED/CANCELLED）
     */
    private String status;

    /**
     * 变更类型过滤（CREATE/SUSPEND/RESUME/CLOSE/RESET_CREDENTIAL）
     */
    private String opType;

    /**
     * 关键字（命中 单号/统一社会信用代码/公司名称）
     */
    private String keyword;
}
