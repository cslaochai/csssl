package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CRL 吊销列表视图：吊销记录 + 当前全量 CRL 元信息（管理页）。
 * default 域时 orgKey 保持 null（字段不输出，存量 URL 响应体逐字节兼容）。
 */
@Data
public class CrlViewVO {

    /**
     * 归属机构域（仅非 default 域输出）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String orgKey;

    /**
     * 吊销记录列表
     */
    private List<CertVO> revoked;

    /**
     * 当前全量 CRL PEM（无吊销记录时空串）
     */
    private String crlPem;

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
     * 吊销条目数
     */
    private int entryCount;

    /**
     * 签发者（CA）CN
     */
    private String issuerCn;
}
