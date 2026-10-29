package com.csssl.dto;

import lombok.Data;

/**
 * 审计哈希链校验区间查询（fromId/toId 均可选，缺省全链）。
 */
@Data
public class ChainVerifyQueryDTO {

    /** 起始日志ID（含） */
    private Long fromId;

    /** 结束日志ID（含） */
    private Long toId;
}
