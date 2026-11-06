package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外证书状态视图（按序列号便捷查询；实时性要求高时以 OCSP 为准）。
 */
@Data
public class ApiCertStatusVO {

    private String serialHex;
    private String status;
    private String statusText;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime revokedAt;
    private String revokeReason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validFrom;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validTo;
}
