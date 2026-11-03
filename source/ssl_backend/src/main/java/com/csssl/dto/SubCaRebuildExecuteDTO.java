package com.csssl.dto;

import lombok.Data;

import java.util.List;

/**
 * 二级CA 重建执行请求：提交 ≥3 份根私钥份额 JSON 加载根私钥后重建二级CA。
 */
@Data
public class SubCaRebuildExecuteDTO {

    /**
     * 根私钥份额 JSON 列表（至少 3 份，编号不同）。
     * 当 useCollectedShares=true 时本字段可省略，改为使用审批阶段已收集的保管人份额。
     */
    private List<String> shareJsons;

    /**
     * 是否使用审批阶段已收集的保管人份额执行重建（默认 false）。
     * 3 名保管人审批同意时各自提交了本人份额，置 true 后服务端直接从审批记录中收集 ≥阈值 份额解封根私钥，
     * 申请人无需再手动粘贴份额。
     */
    private Boolean useCollectedShares;
}
