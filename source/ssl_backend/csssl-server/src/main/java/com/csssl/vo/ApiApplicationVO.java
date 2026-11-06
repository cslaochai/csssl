package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外申请单视图：状态轮询与幂等重放共用。
 */
@Data
public class ApiApplicationVO {

    private String applyNo;
    private String externalRequestId;
    private String applicantType;
    private String applicantName;
    private String subjectDn;
    private String templateCode;
    private String status;
    private String statusText;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submittedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;
    private String reviewedByName;
    private String reviewComment;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime issuedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ApiCertificateVO certificate;
    /**
     * true=同一 externalRequestId 的幂等重放（返回首次结果，未重复建单）
     */
    private boolean idempotentReplay;
}
