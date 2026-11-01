package com.csssl.vo;

import lombok.Data;

/**
 * 租户变更单接入凭证回执：secret 仅在本次响应返回一次（发起人本人领取），不落库、不落日志。
 */
@Data
public class TenantOpReceiptVO {

    private String opNo;
    private String opType;
    private String tenantUscc;
    private String tenantName;
    private String appId;
    private String secret;
    private Integer secretVersion;
}
