package com.csssl.dto;

import lombok.Data;

/**
 * 二级CA 重建申请请求：发起高敏操作，路由至该公司 5 名密钥保管人审批。
 */
@Data
public class SubCaRebuildApplyDTO {

    /**
     * 目标公司（统一社会信用代码）
     */
    private String companyUscc;

    /**
     * 重建事由（审批依据，必填）
     */
    private String reason;
}
