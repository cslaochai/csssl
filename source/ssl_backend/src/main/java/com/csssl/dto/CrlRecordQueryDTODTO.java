package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 吊销记录分页查询（keyword 命中 CN/序列号、status=REVOKED/FROZEN、reason 吊销原因）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CrlRecordQueryDTODTO extends PageParam {

    /**
     * 机构数据域过滤
     */
    private String orgKey;

    /**
     * 关键字（命中 主体CN/序列号）
     */
    private String keyword;

    /**
     * 状态精确过滤（REVOKED/FROZEN）
     */
    private String status;

    /**
     * 吊销原因过滤
     */
    private String reason;
}
