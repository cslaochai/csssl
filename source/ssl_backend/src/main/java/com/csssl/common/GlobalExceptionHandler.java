package com.csssl.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：按异常语义设真实 HTTP 状态码（400/403/500），响应体仍沿用统一 Result 协议。
 *
 * <p>对外文案只保留"入参校验"这类可安全回显的信息；运行期与权限细节一律写日志，
 * 避免异常消息泄露密钥路径、内部状态与权限矩阵。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** DTO 校验注解不通过（@Valid）：取第一条字段错误回显，与 IllegalArgumentException 同为 400 */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<Result<Void>> handleBind(org.springframework.validation.BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(org.springframework.validation.FieldError::getDefaultMessage)
                .orElse("入参校验不通过");
        log.warn("[参数错误] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    /** 参数校验/业务规则不满足（面向入参，消息本身即校验口径，可回显） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[参数错误] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }

    /** 运行期错误（根CA未初始化、私钥缺失、签发失败等）：原始消息只进日志 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        log.error("[系统错误] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "系统繁忙，操作未能完成"));
    }

    /** 权限不足（GM/T 0034 多角色分权）：所需/当前角色由 RoleCode 写日志，响应不echo */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<Void>> handleSecurity(SecurityException e) {
        log.warn("[权限拦截] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(HttpStatus.FORBIDDEN.value(), "权限不足"));
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("[系统异常]", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "系统繁忙，请稍后重试"));
    }
}
