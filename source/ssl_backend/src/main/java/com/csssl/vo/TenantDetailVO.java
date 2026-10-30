package com.csssl.vo;

import com.csssl.entity.CaTenant;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户详情视图对象：租户档案 + 嵌套根CA状态摘要（详情接口与新建租户回执共用）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantDetailVO {

    /**
     * 租户标识
     */
    private String tenantKey;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 证书主体O
     */
    private String subjectO;

    /**
     * 根CA算法：RSA2048 / SM2
     */
    private String algorithm;

    /**
     * 租户状态：ACTIVE / SUSPENDED / CLOSED
     */
    private String status;

    /**
     * 联系人
     */
    private String contact;

    /**
     * 说明
     */
    private String description;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 根CA/SUB_CA 状态摘要
     */
    private TenantVO rootStatus;

    /**
     * 租户档案部分（rootStatus 由服务层填充）
     */
    public static TenantDetailVO from(CaTenant tenant) {
        TenantDetailVO vo = new TenantDetailVO();
        vo.setTenantKey(tenant.getTenantKey());
        vo.setTenantName(tenant.getTenantName());
        vo.setSubjectO(tenant.getSubjectO());
        vo.setAlgorithm(tenant.getAlgorithm());
        vo.setStatus(tenant.getStatus());
        vo.setContact(tenant.getContact());
        vo.setDescription(tenant.getDescription());
        vo.setCreatedAt(tenant.getCreatedAt());
        return vo;
    }
}
