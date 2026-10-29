package com.csssl.vo;

import com.csssl.entity.CaOrg;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构行视图对象：列表/分页/单机构状态摘要共用（含根CA与SUB_CA状态摘要）。
 * 未取到的可选字段（SUB_CA 明细、statusError）序列化时省略，与原 Map 出参逐 key 对齐。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrgVO {

    /**
     * 机构标识
     */
    private String orgKey;

    /**
     * 机构名称
     */
    private String orgName;

    /**
     * 证书主体O
     */
    private String subjectO;

    /**
     * 根CA算法：RSA2048 / SM2
     */
    private String algorithm;

    /**
     * 机构状态：ACTIVE / SUSPENDED / CLOSED
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
     * 单机构状态读取异常原因（如密钥文件损坏；不拖垮整个列表）
     */
    private String statusError;

    /**
     * 机构档案部分（根CA状态字段由服务层按机构实时状态填充）
     */
    public static OrgVO from(CaOrg org) {
        OrgVO vo = new OrgVO();
        vo.setOrgKey(org.getOrgKey());
        vo.setOrgName(org.getOrgName());
        vo.setSubjectO(org.getSubjectO());
        vo.setAlgorithm(org.getAlgorithm());
        vo.setStatus(org.getStatus());
        vo.setContact(org.getContact());
        vo.setDescription(org.getDescription());
        vo.setCreatedAt(org.getCreatedAt());
        return vo;
    }
}
