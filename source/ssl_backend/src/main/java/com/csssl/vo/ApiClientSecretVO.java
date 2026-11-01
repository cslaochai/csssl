package com.csssl.vo;

import lombok.Data;

/**
 * API 客户端登记/轮换回执：secret 仅在本次响应中返回一次，不落日志、不可再次查询。
 */
@Data
public class ApiClientSecretVO {

    private Long id;
    private String appId;
    private String tenantKey;
    private String tenantName;
    private String clientName;
    private String secret;
    private Integer secretVersion;
}
