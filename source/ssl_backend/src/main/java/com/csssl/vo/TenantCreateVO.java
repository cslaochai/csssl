package com.csssl.vo;

import lombok.Data;

/**
 * 新建租户回执：建租户同时发放默认接入凭证，secret 仅在本次响应返回一次，不落库、不落日志。
 */
@Data
public class TenantCreateVO {

    private String tenantUscc;
    private String tenantName;
    private String subjectO;
    private String algorithm;
    private String appId;
    private String secret;
    private Integer secretVersion;
}
