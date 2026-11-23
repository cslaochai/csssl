package com.csssl.common;

/**
 * 对外 API 业务异常：携带 HTTP 状态码，响应体仍为统一 Result 协议。
 * 仅用于 /ca/api/** 的契约化错误（401/403/404/409/413/422/429/503）。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
