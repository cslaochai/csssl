package com.csssl.dto;

import lombok.Data;

/**
 * 申请审核意见请求（approve/reject 共用；approve 的 body 可整体省略）。
 */
@Data
public class ApplicationAuditDTO {

    /**
     * 目标单据 ID（来自 body，替换原来的 @PathVariable；approve 可整体省略 body，此时传独立 IdDTO）
     */
    private Long id;

    /**
     * 公司数据域（全局用户显式指定；替换原来的 @RequestParam companyUscc）
     */
    private String companyUscc;

    /**
     * 审核意见
     */
    private String comment;
}
