package com.csssl.vo;

import com.csssl.entity.ApiClient;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部 API 客户端视图（管理端列表/登记回执）；绝不包含 secret。
 */
@Data
public class ApiClientVO {

    private Long id;
    private String appId;
    private String companyUscc;
    private String companyName;
    private String clientName;
    private String systemName;
    private String ipAllowlist;
    private String clientCertSha256;
    private Integer secretVersion;
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUsedAt;
    private String createdBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rotatedAt;

    public static ApiClientVO from(ApiClient c) {
        ApiClientVO vo = new ApiClientVO();
        vo.setId(c.getId());
        vo.setAppId(c.getAppId());
        vo.setCompanyUscc(c.getCompanyUscc());
        vo.setClientName(c.getClientName());
        vo.setSystemName(c.getSystemName());
        vo.setIpAllowlist(c.getIpAllowlist());
        vo.setClientCertSha256(c.getClientCertSha256());
        vo.setSecretVersion(c.getSecretVersion());
        vo.setStatus(c.getStatus());
        vo.setLastUsedAt(c.getLastUsedAt());
        vo.setCreatedAt(c.getCreatedAt());
        vo.setRotatedAt(c.getRotatedAt());
        return vo;
    }
}
