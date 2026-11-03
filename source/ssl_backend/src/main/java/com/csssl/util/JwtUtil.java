package com.csssl.util;

import com.csssl.config.CssslProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 会话工具（与 csmig 的 JwtUtils 同方案：HMAC-SHA 签名 + 24h 默认有效期）。
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final CssslProperties cssslProperties;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(cssslProperties.getSecurity().getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 令牌解析结果（uid/tv 供拦截器回查会话版本号，实现吊销；realName 为登录时真实姓名快照） */
    public record TokenClaims(String username, String role, Long uid, Integer tokenVersion, String realName) {
    }

    /**
     * 签发会话令牌：subject=username，携带角色与 uid/tv（tv=签发时的 token_version）。
     * 口令/角色/状态变更后库里 token_version+1，旧令牌即刻与库中版本不符而被拒。
     *
     * <p>realName 写入 claim 'rn'（登录时真实姓名快照）。令牌不含公司域：用户不再有归属公司，
     * 操作哪家公司由请求参数显式指定、由服务端按公司域口径收口。</p>
     */
    public String createToken(String username, String role, Long uid, Integer tokenVersion, String realName) {
        long exp = cssslProperties.getSecurity().getJwt().getExpiration();
        return Jwts.builder()
                .subject(username)
                .claim("username", username)
                .claim("role", role == null ? "" : role)
                .claim("uid", uid)
                .claim("tv", tokenVersion == null ? 0 : tokenVersion)
                .claim("rn", realName == null ? "" : realName)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + exp))
                .signWith(signingKey())
                .compact();
    }

    /** 解析并校验令牌，一次解出全部 claim；非法/过期返回 null */
    public TokenClaims parse(String token) {
        try {
            Claims payload = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new TokenClaims(
                    payload.get("username", String.class),
                    payload.get("role", String.class),
                    asLong(payload.get("uid")),
                    asInt(payload.get("tv")),
                    payload.get("rn", String.class));
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析用户名；非法/过期返回 null */
    public String parseUsername(String token) {
        TokenClaims claims = parse(token);
        return claims == null ? null : claims.username();
    }

    /** 解析令牌中的角色；非法/过期返回 null */
    public String parseRole(String token) {
        TokenClaims claims = parse(token);
        return claims == null ? null : claims.role();
    }

    /** JWT 反序列化后小整数会落成 Integer，统一按 Number 取值，避免类型分支踩坑 */
    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
