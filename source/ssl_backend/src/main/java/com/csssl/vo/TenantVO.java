package com.csssl.vo;

import com.csssl.entity.CaTenant;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户行视图对象：列表/分页/单租户状态摘要共用（含根CA与SUB_CA状态摘要）。
 * 未取到的可选字段（SUB_CA 明细、statusError）序列化时省略，与原 Map 出参逐 key 对齐。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantVO {

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
     * 是否已初始化根CA
     */
    private boolean hasRoot;

    /**
     * 根CA序列号
     */
    private String rootSerialHex;

    /**
     * 根CA证书CN
     */
    private String rootCn;

    /**
     * 根CA状态
     */
    private String rootStatus;

    /**
     * 根CA生效时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rootValidFrom;

    /**
     * 根CA过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rootValidTo;

    /**
     * 根私钥状态：ONLINE / SEALED / SHARES / MISSING
     */
    private String rootKeyState;

    /**
     * SUB_CA 序列号
     */
    private String subCaSerialHex;

    /**
     * SUB_CA 状态
     */
    private String subCaStatus;

    /**
     * SUB_CA 生效时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime subCaValidFrom;

    /**
     * SUB_CA 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime subCaValidTo;

    /**
     * 单租户状态读取异常原因（如密钥文件损坏；不拖垮整个列表）
     */
    private String statusError;

    /**
     * 租户档案部分（根CA状态字段由服务层按租户实时状态填充）
     */
    public static TenantVO from(CaTenant tenant) {
        TenantVO vo = new TenantVO();
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
