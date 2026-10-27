package com.csssl.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书链在线校验结果。
 */
@Data
public class VerifyResult {

    /** 是否通过（链可信 + 未过期 + 未吊销 + 用途匹配） */
    private boolean valid;

    /** 不通过时的原因 */
    private String message;

    private String serialHex;
    private String subjectCn;
    private String issuerCn;
    private String algorithm;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
}
