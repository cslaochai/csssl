package com.csssl.common;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询基类：pageNo/pageSize 全站统一口径（GET 查询串自动绑定，缺省 1/10）。
 */
@Data
public class PageParam {

    /** 页码（从 1 起） */
    @Min(value = 1, message = "页码最小为 1")
    private long pageNo = 1;

    /** 每页条数 */
    @Min(value = 1, message = "每页条数最小为 1")
    private long pageSize = 10;
}
