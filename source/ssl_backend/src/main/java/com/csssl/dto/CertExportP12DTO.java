package com.csssl.dto;

import lombok.Data;

/**
 * 导出 PKCS#12 请求（body 可整体省略；口令缺省由服务端生成随机口令）。
 */
@Data
public class CertExportP12DTO {

    /**
     * P12 口令（缺省服务端生成）
     */
    private String password;
}
