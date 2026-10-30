package com.csssl.dto;

import lombok.Data;

/**
 * 证书吊销/挂起请求（body 可整体省略）。
 */
@Data
public class CertRevokeDTO {

    /**
     * 吊销/挂起原因
     */
    private String reason;
}
