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

    /** 签发会话令牌：subject=username，默认有效期 24h */
    public String createToken(String username) {
        long exp = cssslProperties.getSecurity().getJwt().getExpiration();
        return Jwts.builder()
                .subject(username)
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + exp))
                .signWith(signingKey())
                .compact();
    }

    /** 解析并校验令牌；非法/过期返回 null */
    public String parseUsername(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.get("username", String.class);
        } catch (Exception e) {
            return null;
        }
    }
}
