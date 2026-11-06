package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 哈希链对账结果视图对象：分页扫描逐条核验签名与 prev_id/prev_sign 衔接。
 */
@Data
public class ChainVerifyVO {

    /**
     * 实际对账记录条数
     */
    private long checked;

    /**
     * 检出的断裂明细列表（空列表=链完整）
     */
    private List<ChainBrokenVO> broken;
}
