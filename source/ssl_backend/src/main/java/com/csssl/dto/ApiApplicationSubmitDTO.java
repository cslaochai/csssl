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
     * 申请人类别：历史上由调用方传入；当前对外 API 通道只发医生个人客户端证书，
     * 服务端一律按 PERSON 落库，本字段仅做协议兼容保留，传入值会被忽略。
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
     * 所属系统（= 证书主体 O）；由接入客户端的 systemName 透传，缺省时用该公司首个系统名兜底
     */
    private String systemName;

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
     * HIS 员工主键（历史字段，已废弃：对外 API 通道只认工号，本字段传入值会被忽略）
     */
    @Size(max = 64, message = "externalEmployeeId 不超过64字符")
    @Deprecated
    private String externalEmployeeId;

    /**
     * 工号（必填）：医生在本公司内的唯一身份锚点。
     * 同一公司下工号唯一，CA 侧会强制把它写进证书 DN 的 OU= 字段并做唯一性校验；
     * 外部 API 吊销时也按工号交叉校验，杜绝"拿 A 医生的序列号吊 B 医生的证"。
     */
    @NotBlank(message = "externalEmployeeCode（工号）不能为空：医生身份锚点，同公司下唯一")
    @Size(max = 64, message = "externalEmployeeCode 不超过64字符")
    private String externalEmployeeCode;

    /**
     * 科室名称（展示用；不进 DN，避免科室调整导致换证）
     */
    @Size(max = 64, message = "externalDeptName 不超过64字符")
    private String externalDeptName;

    /**
     * 对外 API 通道只发医生个人客户端证书，applicantType 由服务端强制为 PERSON，本字段不再参与身份校验。
     */
    @AssertTrue(message = "对外 API 通道必须携带 externalEmployeeCode（工号）")
    public boolean isPersonIdentified() {
        return externalEmployeeCode != null && !externalEmployeeCode.isBlank();
    }
}
