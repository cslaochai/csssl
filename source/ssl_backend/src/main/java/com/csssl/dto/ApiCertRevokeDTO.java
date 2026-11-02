package com.csssl.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 外部系统吊销证书：序列号、医生身份与原因放在请求体内，URL 不暴露参数。
 *
 * <p>作用域铁律：一次请求只吊销一张证书，且必须落到"指定医生"上。服务端会拿
 * externalEmployeeCode / externalEmployeeId 与该证书申请单的记录做交叉校验，
 * 不一致直接 403，避免拿 A 的序列号配 B 的身份吊销，也避免越权吊他人证书。
 */
@Data
public class ApiCertRevokeDTO {

    @NotBlank(message = "serialHex 不能为空")
    private String serialHex;

    /**
     * HIS 员工主键（与申请单记录交叉校验；与工号至少填一个）
     */
    @Size(max = 64, message = "externalEmployeeId 不超过64字符")
    private String externalEmployeeId;

    /**
     * 工号（与申请单记录交叉校验；与员工主键至少填一个）
     */
    @Size(max = 64, message = "externalEmployeeCode 不超过64字符")
    private String externalEmployeeCode;

    /**
     * 吊销原因必填：会写入 CRL 条目与审计日志，事后必须能回答"为什么吊、谁主张的"
     */
    @NotBlank(message = "reason 不能为空（吊销原因会进 CRL 与审计，必须写明）")
    @Size(min = 4, max = 200, message = "reason 长度 4-200 字符")
    private String reason;

    @AssertTrue(message = "吊销必须指定到医生：externalEmployeeCode 与 externalEmployeeId 至少填一个")
    public boolean isEmployeeIdentified() {
        return notBlank(externalEmployeeId) || notBlank(externalEmployeeCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
