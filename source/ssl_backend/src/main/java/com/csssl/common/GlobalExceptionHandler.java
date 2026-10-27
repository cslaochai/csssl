package com.csssl.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：参数类错误返回 400，业务运行错误返回 500，统一 Result 协议。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验/业务规则不满足（如"主体CN不能为空"） */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }

    /** 运行期错误（根CA未初始化、私钥缺失、签发失败等） */
    @ExceptionHandler(IllegalStateException.class)
    public Result<Void> handleIllegalState(IllegalStateException e) {
        log.error("[系统错误] {}", e.getMessage());
        return Result.error(500, e.getMessage());
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[系统异常]", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
