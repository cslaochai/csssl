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

    /**
     * 审批人提交的根私钥份额（秘钥片）JSON：必须为该保管人本人持有的那份（index/yHex 与绑定一致）。
     * 审批同意时必填，3 名保管人通过即收集到 ≥3 份份额，用于执行重建时解封根私钥。
     */
    private String shareJson;
}
