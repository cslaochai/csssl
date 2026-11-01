package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外证书视图（申请状态里内嵌）：PEM 与链都由服务端从证书总账重新组装，不含私钥。
 */
@Data
public class ApiCertificateVO {

    private String serialHex;
    private String subjectDn;
    private String issuerDn;
    private String algorithm;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime notBefore;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime notAfter;
    /**
     * SHA-256 指纹（大写十六进制，冒号分隔）
     */
    private String fingerprintSha256;
    /**
     * 叶子证书 PEM
     */
    private String certPem;
    /**
     * 证书链 PEM（叶子 + 签发 SUB_CA）
     */
    private String chainPem;
}
