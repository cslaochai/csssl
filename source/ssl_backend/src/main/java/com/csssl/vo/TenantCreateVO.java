package com.csssl.vo;

import lombok.Data;

/**
 * 新建租户回执视图对象：租户标识 + 根CA状态摘要 + 该租户的接入凭证（secret 仅本次返回一次）。
 */
@Data
public class TenantCreateVO {

    private String tenantKey;
    private String uscc;
    private TenantVO rootStatus;

    /**
     * 默认接入客户端记录 ID（后续重置密钥用）
     */
    private Long apiClientId;

    /**
     * 接入 ID（App ID）：外部系统写入配置，随请求头 X-App-Id 发送
     */
    private String apiAppId;

    /**
     * 接入密钥：仅本次响应展示，请立即转交外部系统保存
     */
    private String apiSecret;

    private Integer secretVersion;
}
