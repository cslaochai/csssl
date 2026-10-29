package com.csssl.vo;

import com.csssl.entity.CaCert;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书视图对象：管理后台列表/详情渲染用，不携带私钥。
 */
@Data
public class CertVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String orgKey;
    private String serialHex;
    private String subjectCn;
    private String certType;
    private String algorithm;
    private String issuerCn;
    private String san;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validFrom;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validTo;
    private String status;
    private String revokeReason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime revokedAt;
    private String description;
    private String createdBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public static CertVO from(CaCert c) {
        CertVO vo = new CertVO();
        vo.setId(c.getId());
        vo.setOrgKey(c.getOrgKey());
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
