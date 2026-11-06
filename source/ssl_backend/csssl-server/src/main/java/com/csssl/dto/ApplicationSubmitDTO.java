package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交证书申请请求（录入员代录；身份材料由受理公司留存，平台只收主体与公钥）。
 */
@Data
public class ApplicationSubmitDTO {

    /**
     * 受理公司（必须显式指定）
     */
    @NotBlank(message = "参数 companyUscc 不能为空（受理公司）")
    private String companyUscc;

    /**
     * 所属系统（= 证书主体 O）；缺省时用该公司首个系统名兜底
     */
    private String systemName;

    /**
     * 申请人类别（证书主体类型）：INSTITUTION-单位（系统/法人等非个人主体） / PERSON-个人（自然人）；与公司公司域无关
     */
    @NotBlank(message = "申请人类别不能为空")
    private String applicantType;

    /**
     * 申请人/公司名称
     */
    @NotBlank(message = "参数 applicantName 不能为空（申请人/公司名称）")
    private String applicantName;

    /**
     * 主体DN（如 CN=张三,O=麓康医院）；只放姓名/公司等稳定身份，科室等易变属性不进 DN
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

    /**
     * 提交时指定的审批人（用户ID，单选）：必须来自流程首个审批节点权限标识筛选出的候选人，
     * 由前端下拉选择；不传则按节点配置自动分派。
     */
    private Long assigneeUserId;

    /**
     * 提交时指定的审批人账号（历史字段，保留兼容；新前端改用 assigneeUserId）
     */
    private String assigneeUsername;

    /**
     * 提交时指定的审批人真实姓名（前端选中后回填展示，后端以账号校验为准）
     */
    private String assigneeRealName;
}
