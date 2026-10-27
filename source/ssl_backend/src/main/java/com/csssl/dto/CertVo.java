package com.csssl.dto;

import com.csssl.entity.CaCert;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书视图对象：管理后台列表/详情渲染用，不携带私钥。
 */
@Data
public class CertVo {

    private Long id;
    private String serialHex;
    private String subjectCn;
    private String certType;
    private String algorithm;
    private String issuerCn;
    private String san;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String status;
    private String revokeReason;
    private LocalDateTime revokedAt;
    private String description;
    private String createdBy;
    private LocalDateTime createdAt;

    public static CertVo from(CaCert c) {
        CertVo vo = new CertVo();
        vo.setId(c.getId());
        vo.setSerialHex(c.getSerialHex());
        vo.setSubjectCn(c.getSubjectCn());
        vo.setCertType(c.getCertType());
        vo.setAlgorithm(c.getAlgorithm());
        vo.setIssuerCn(c.getIssuerCn());
        vo.setSan(c.getSan());
        vo.setValidFrom(c.getValidFrom());
        vo.setValidTo(c.getValidTo());
        vo.setStatus(c.getStatus());
        vo.setRevokeReason(c.getRevokeReason());
        vo.setRevokedAt(c.getRevokedAt());
        vo.setDescription(c.getDescription());
        vo.setCreatedBy(c.getCreatedBy());
        vo.setCreatedAt(c.getCreatedAt());
        return vo;
    }
}
