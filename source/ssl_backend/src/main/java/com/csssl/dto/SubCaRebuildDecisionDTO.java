package com.csssl.dto;

import lombok.Data;

/**
 * 二级CA 重建申请审批/驳回请求（审批意见；驳回必填）。
 */
@Data
public class SubCaRebuildDecisionDTO {

    /**
     * 审批意见（驳回必填）
     */
    private String comment;
}
