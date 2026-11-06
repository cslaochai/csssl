package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书链在线校验结果。
 */
@Data
public class VerifyResultVO {

    /**
     * 是否通过（链可信 + 未过期 + 未吊销 + 用途匹配）
     */
    private boolean valid;
    /**
     * 证书状态三态（对齐 OCSP RFC 6960）：
     * good=在签发范围内且未被吊销；revoked=已吊销/挂起；unknown=非本公司签发或序列号未命中。
     * 前端按此字段展示状态，不再自行从 valid 推断。
     */
    private String status;
    /**
     * 状态中文说明（status 的可读文案）
     */
    private String statusText;

    /**
     * 不通过时的原因
     */
    private String message;

    private String serialHex;
    private String subjectCn;
    private String issuerCn;
    private String algorithm;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validFrom;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validTo;
}
