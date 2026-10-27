package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.LoginRequest;
import com.csssl.service.AuditService;
import com.csssl.service.AuthService;
import com.csssl.util.Sm2Util;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final com.csssl.util.JwtUtil jwtUtil;

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
