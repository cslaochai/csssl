package com.csssl.config;

import com.csssl.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.csssl.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管理接口登录拦截：/admin/** 需要 Authorization: Bearer <token>，未登录返回 401。
 * 拦截成功后把登录用户名写入 request attribute（审计/创建人用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USERNAME = "ssl.username";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        String username = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            username = jwtUtil.parseUsername(auth.substring(7));
        }
        if (username == null) {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(MAPPER.writeValueAsString(Result.error(401, "未登录或登录已过期")));
            return false;
        }
        request.setAttribute(ATTR_USERNAME, username);
        return true;
    }
}
