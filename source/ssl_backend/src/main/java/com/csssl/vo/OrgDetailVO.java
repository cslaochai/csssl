package com.csssl.vo;

import com.csssl.entity.CaOrg;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构详情视图对象：机构档案 + 嵌套根CA状态摘要（详情接口与新建机构回执共用）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrgDetailVO {

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
     * 根CA/SUB_CA 状态摘要
     */
    private OrgVO rootStatus;

    /**
     * 机构档案部分（rootStatus 由服务层填充）
     */
    public static OrgDetailVO from(CaOrg org) {
        OrgDetailVO vo = new OrgDetailVO();
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
