package com.csssl.vo;

import lombok.Data;

/**
 * 审计归档导出视图对象：CSV 全文 + 审计报告 SM2 签名（归档文件完整性可在线验签）。
 */
@Data
public class AuditExportVO {

    /**
     * CSV 全文（UTF-8 BOM 开头）
     */
    private String csv;

    /**
     * 对 CSV 全文的 SM3withSM2 签名（hex）；签名失败时为空串
     */
    private String sign;

    /**
     * 导出记录条数
     */
    private int count;
}
