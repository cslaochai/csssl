package com.csssl.vo;

import com.csssl.entity.CaCompany;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公司详情视图对象：公司档案 + 嵌套根CA状态摘要（详情接口与新建公司回执共用）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompanyDetailVO {

    /**
     * 统一社会信用代码：公司唯一标识
     */
    private String uscc;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 证书主体O
     */
    private String subjectO;

    /**
     * 根CA算法：RSA2048 / SM2
     */
    private String algorithm;

    /**
     * 公司状态：ACTIVE / SUSPENDED / CLOSED
     */
    private String status;

    /**
     * 联系人姓名
     */
    private String contact;

    /**
     * 联系人手机号（列表/详情展示做掩码）
     */
    private String contactPhone;

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
    private CompanyVO rootStatus;

    /**
     * 公司档案部分（rootStatus 由服务层填充）
     */
    public static CompanyDetailVO from(CaCompany company) {
        CompanyDetailVO vo = new CompanyDetailVO();
        vo.setUscc(company.getUscc());
        vo.setCompanyName(company.getCompanyName());
        vo.setSubjectO(company.getSubjectO());
        vo.setAlgorithm(company.getAlgorithm());
        vo.setStatus(company.getStatus());
        vo.setContact(company.getContact());
        vo.setContactPhone(company.getContactPhone());
        vo.setDescription(company.getDescription());
        vo.setCreatedAt(company.getCreatedAt());
        return vo;
    }
}
