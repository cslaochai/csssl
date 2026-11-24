package com.csssl.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 哈希链断裂明细视图对象：仅暴露断链记录 id 与原因，不含记录内容。
 */
@Data
public class ChainBrokenVO {

    /**
     * 断裂记录 id（转字符串，防 JS 大数精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 断裂原因（签名缺失/验签失败/前驱缺失/分叉/链改写等）
     */
    private String reason;
}
