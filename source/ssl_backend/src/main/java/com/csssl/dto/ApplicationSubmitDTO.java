package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交证书申请请求（录入员代录；身份材料由受理机构留存，平台只收主体与公钥）。
 */
@Data
public class ApplicationSubmitDTO {

    /**
     * 受理机构（缺省走 ?orgKey= / 登录域归一）
     */
    private String orgKey;

    /**
     * 申请人类别：INSTITUTION-机构 / PERSON-个人
     */
    @NotBlank(message = "申请人类别不能为空")
    private String applicantType;

    /**
     * 申请人/机构名称
     */
    @NotBlank(message = "参数 applicantName 不能为空（申请人/机构名称）")
    private String applicantName;

    /**
     * 主体DN（如 CN=张三,O=麓康医院,OU=门诊部）
     */
    @NotBlank(message = "参数 subjectDn 不能为空（主体DN）")
    private String subjectDn;

    /**
     * 证书模板ID
     */
    @NotNull(message = "参数 templateId 不能为空（证书模板ID）")
    private Long templateId;

    /**
     * 公钥PEM（申请方自持私钥，仅提交公钥）
     */
    @NotBlank(message = "公钥PEM不能为空")
    private String publicKeyPem;

    /**
     * SAN 扩展（可选）
     */
    private String san;

    /**
     * 用途说明（可选）
     */
    private String description;
}
