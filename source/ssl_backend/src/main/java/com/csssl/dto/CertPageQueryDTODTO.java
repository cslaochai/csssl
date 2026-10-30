package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 证书分页查询（certType/status 精确、keyword 命中 CN/序列号、orgKey 域过滤）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CertPageQueryDTODTO extends PageParam {

    /**
     * 证书类型精确过滤（SERVER/CLIENT/SUB_CA）
     */
    private String certType;

    /**
     * 证书状态精确过滤（VALID/REVOKED/FROZEN/EXPIRED）
     */
    private String status;

    /**
     * 关键字（命中 主体CN/序列号）
     */
    private String keyword;

    /**
     * 机构数据域过滤
     */
    private String orgKey;
}
