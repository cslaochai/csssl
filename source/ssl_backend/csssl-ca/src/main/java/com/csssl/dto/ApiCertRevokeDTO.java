package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 外部系统吊销证书：序列号、公钥指纹与原因放在请求体内，URL 不暴露参数。
 *
 * <p>作用域铁律：一次请求只吊销一张证书。身份校验靠公钥指纹——
 * 没有原始公钥就算不出正确指纹，杜绝冒用吊销，不再传工号/员工主键。
 */
@Data
public class ApiCertRevokeDTO {

    @NotBlank(message = "serialHex 不能为空")
    private String serialHex;

    /**
     * 申请时公钥的 SHA-256 指纹（hex，小写）：吊销时回传，CA 与证书里的公钥指纹比对，
     * 这是吊销的强身份校验——没有原始公钥就算不出正确指纹，杜绝冒用吊销。
     */
    @NotBlank(message = "publicKeyFingerprint 不能为空：吊销必须回传申请时公钥的 SHA-256 指纹")
    @Size(max = 128, message = "publicKeyFingerprint 不超过128字符")
    private String publicKeyFingerprint;

    /**
     * 吊销原因必填：会写入 CRL 条目与审计日志，事后必须能回答"为什么吊、谁主张的"
     */
    @NotBlank(message = "reason 不能为空（吊销原因会进 CRL 与审计，必须写明）")
    @Size(min = 1, max = 200, message = "吊销原因长度 4-200 字符")
    private String reason;
}
