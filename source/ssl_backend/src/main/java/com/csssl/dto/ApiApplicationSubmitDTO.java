package com.csssl.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 外部系统（HIS 等）提交证书申请请求：模板用 templateCode 引用（外部系统不感知内部雪花 ID），
 * externalRequestId 为幂等键；身份材料由受理公司留存，平台只收主体与公钥。
 */
@Data
public class ApiApplicationSubmitDTO {

    /**
     * 外部系统申请唯一标识（同公司同客户端内幂等；断网重试必须复用）
     */
    @NotBlank(message = "externalRequestId 不能为空（幂等键）")
    @Size(max = 64, message = "externalRequestId 不超过64字符")
    @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "externalRequestId 仅允许字母、数字与 . _ : -")
    private String externalRequestId;

    /**
     * 申请人类别：INSTITUTION-单位 / PERSON-个人
     */
    @NotBlank(message = "applicantType 不能为空（INSTITUTION / PERSON）")
    @Pattern(regexp = "INSTITUTION|PERSON", message = "applicantType 只能是 INSTITUTION 或 PERSON")
    private String applicantType;

    @NotBlank(message = "applicantName 不能为空")
    @Size(max = 64, message = "applicantName 不超过64字符")
    private String applicantName;

    @NotBlank(message = "subjectDn 不能为空（如 CN=张三,O=长沙市麓康医院）")
    @Size(max = 255, message = "subjectDn 不超过255字符")
    private String subjectDn;

    /**
     * 模板编码（如 DOCTOR_SIGN）；必须是全局或本公司模板，SUB_CA 模板拒绝
     */
    @NotBlank(message = "templateCode 不能为空")
    private String templateCode;

    @NotBlank(message = "publicKeyPem 不能为空（仅公钥，禁传私钥）")
    private String publicKeyPem;

    @Size(max = 500, message = "san 不超过500字符")
    private String san;

    @Size(max = 500, message = "description 不超过500字符")
    private String description;

    /**
     * HIS 员工主键（对账用，可空）
     */
    @Size(max = 64, message = "externalEmployeeId 不超过64字符")
    private String externalEmployeeId;

    /**
     * 工号（对账用，可空）
     */
    @Size(max = 64, message = "externalEmployeeCode 不超过64字符")
    private String externalEmployeeCode;

    /**
     * 科室名称（展示用；不进 DN，避免科室调整导致换证）
     */
    @Size(max = 64, message = "externalDeptName 不超过64字符")
    private String externalDeptName;

    /**
     * 个人证书必须带医生身份锚点：吊销时要拿它做交叉校验，申请时不留就没法验。
     */
    @AssertTrue(message = "applicantType=PERSON 时必须填写医生身份：externalEmployeeCode 与 externalEmployeeId 至少填一个")
    public boolean isPersonIdentified() {
        return !"PERSON".equalsIgnoreCase(applicantType)
                || notBlank(externalEmployeeId)
                || notBlank(externalEmployeeCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
