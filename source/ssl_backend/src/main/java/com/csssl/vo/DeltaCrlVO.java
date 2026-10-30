package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 增量 CRL（dCRL）视图：指定租户域自最近全量发布以来新增的吊销/挂起条目。
 * default 域时 tenantKey 保持 null（字段不输出，存量 URL 响应体逐字节兼容）。
 */
@Data
public class DeltaCrlVO {

    /**
     * 归属租户域（仅非 default 域输出）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String tenantKey;

    /**
     * 增量 CRL PEM
     */
    private String deltaPem;

    /**
     * 本次更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime thisUpdate;

    /**
     * 下次更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime nextUpdate;

    /**
     * 增量条目数
     */
    private int entryCount;

    /**
     * 本增量 CRL 编号
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private long crlNumber;

    /**
     * 基于的全量 CRL 编号
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private long baseCrlNumber;
}
