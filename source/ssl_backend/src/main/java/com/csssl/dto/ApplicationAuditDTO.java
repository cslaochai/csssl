package com.csssl.dto;

import lombok.Data;

/**
 * 申请审核意见请求（approve/reject 共用；approve 的 body 可整体省略）。
 */
@Data
public class ApplicationAuditDTO {

    /**
     * 审核意见
     */
    private String comment;
}
