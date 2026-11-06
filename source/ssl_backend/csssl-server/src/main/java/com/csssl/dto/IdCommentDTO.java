package com.csssl.dto;

import lombok.Data;

/**
 * 通用：仅 ID + 审批意见（适用于按单据 id 可反查公司域的场景，无需前端显式传 companyUscc）。
 */
@Data
public class IdCommentDTO {

    /**
     * 目标单据 ID
     */
    private Long id;

    /**
     * 审批意见（驳回必填、批准可空）
     */
    private String comment;
}
