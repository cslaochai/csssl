package com.csssl.dto;

import lombok.Data;

/**
 * 公司变更单保管人投票请求（重新指派保管人专用）：
 * 投票人=提交时锁定的原 5 名密钥保管人，每人一票；驳回意见必填，同意意见可空。
 */
@Data
public class CompanyOpVoteDTO {

    /**
     * 目标变更单 ID（来自 body，替换原来的 @PathVariable）
     */
    private Long id;

    /**
     * true-同意 false-驳回
     */
    private Boolean agree;

    /**
     * 投票意见（驳回必填，同意可空）
     */
    private String comment;
}
