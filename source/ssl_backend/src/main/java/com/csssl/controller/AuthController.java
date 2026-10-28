package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.LoginRequest;
import com.csssl.service.AuditService;
import com.csssl.service.AuthService;
import com.csssl.util.JwtUtil;
import com.csssl.util.Sm2Util;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录鉴权接口（公开）：SM2 公钥下发 + 口令登录（对齐 cslk HIS / csmig 协议）。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final Sm2Util sm2Util;
    private final AuthService authService;
    private final AuditService auditService;
    private final JwtUtil jwtUtil;

    /** 登录口令加密公钥（SM2 未压缩公钥点十六进制，04 前缀） */
    @GetMapping("/publicKey")
    public Result<Map<String, String>> publicKey() {
        Map<String, String> data = new HashMap<>();
        data.put("keyId", sm2Util.getKeyId());
        data.put("publicKey", sm2Util.getPublicKeyHex());
        return Result.success("获取公钥成功", data);
    }

    /** 账号口令登录：口令密文为 SM2 C1C3C2 十六进制 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        Map<String, Object> data = authService.login(req, clientIp(request));
        return Result.success("登录成功", data);
    }

    /**
     * 自助改密（需携带登录令牌，/auth/** 不经拦截器故手工解析）：
     * body {oldPassword, newPassword}，均为 SM2 密文（与登录同通道）。
     * 成功后 token_version+1，当前令牌即被吊销，客户端必须重新登录。
     */
    @PostMapping("/changePassword")
    public ResponseEntity<Result<Void>> changePassword(@RequestBody Map<String, String> body,
                                                       HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        JwtUtil.TokenClaims claims = auth != null && auth.startsWith("Bearer ")
                ? jwtUtil.parse(auth.substring(7)) : null;
        if (claims == null || claims.username() == null || claims.username().isBlank()) {
            // /auth/** 不走拦截器，未带有效令牌在这里直接 401（前端按 401 跳登录页）
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未登录或登录已过期"));
        }
        authService.changePassword(claims.username(), claims.tokenVersion(),
                body.get("oldPassword"), body.get("newPassword"), clientIp(request));
        return ResponseEntity.ok(Result.success("口令修改成功，请使用新口令重新登录", null));
    }

    /** 退出登录（/auth/** 不经过拦截器，从 Authorization 头解析用户名） */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        String username = token != null && token.startsWith("Bearer ")
                ? jwtUtil.parseUsername(token.substring(7))
                : "SYSTEM";
        authService.logout(username, clientIp(request));
        return Result.success("已退出登录", null);
    }

    /** 取客户端真实IP（支持前置代理 X-Forwarded-For） */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
