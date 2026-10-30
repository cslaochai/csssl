package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 证书申请单分页查询（status 精确、keyword 命中姓名/主体DN/申请编号、orgKey 域过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationPageQueryDTO extends PageParam {

    /**
     * 申请单状态精确过滤（PENDING/APPROVED/REJECTED/ISSUED/CANCELLED）
     */
    private String status;

    /**
     * 关键字（命中 申请人/主体DN/申请编号）
     */
    private String keyword;

    /**
     * 机构数据域过滤
     */
    private String orgKey;
}
