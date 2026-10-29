package com.csssl.dto;

import lombok.Data;

/**
 * 证书更新/换钥请求（body 可整体省略；有效期缺省沿用原证书）。
 */
@Data
public class CertRenewDTO {

    /** 有效期天数（缺省沿用原证书） */
    private Integer validityDays;
}
