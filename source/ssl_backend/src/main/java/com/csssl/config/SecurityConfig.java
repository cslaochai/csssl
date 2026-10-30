package com.csssl.config;

import com.csssl.common.Result;
import com.csssl.entity.LoginUser;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.PermissionService;
import com.csssl.util.JwtUtil;
import com.csssl.util.UserUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置：无状态 JWT 过滤器链 + @PreAuthorize 方法级权限。
 *
 * <p>口径与原 AuthInterceptor 完全一致：</p>
 * <ul>
 *   <li>/admin/** 需登录（TokenAuthFilter 校验令牌并写入权限点 authorities），其余路径放行
 *       （/auth 登录、/tsa、/ca/public 对外公开，语义不变）；</li>
 *   <li>接口内细粒度权限点由各控制器 @PreAuthorize("hasAuthority('…')") 校验，
 *       判定依据是 TokenAuthFilter 写入的权限点 authorities（角色生效权限）；</li>
 *   <li>401/403 响应沿用统一 Result JSON：未登录 401「未登录或登录已过期」，
 *       权限不足 403「权限不足」，拒绝细节只写日志不回显（不暴露权限矩阵）。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PermissionService permissionService;

    /**
     * 未登录/令牌失效（含令牌非法、账号停用、版本不符、角色停用——TokenAuthFilter 已提前 401，此处兜底缺令牌场景）
     */
    private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
                                     org.springframework.security.core.AuthenticationException e) throws IOException {
        log.warn("[权限拦截] 未登录访问受限接口 uri={}", request.getRequestURI());
        write(response, 401, "未登录或登录已过期");
    }

    /**
     * 已登录但权限不足：细节只写日志，响应泛化
     */
    private static void forbidden(HttpServletRequest request, HttpServletResponse response,
                                  org.springframework.security.access.AccessDeniedException e) throws IOException {
        LoginUser loginUser = UserUtils.getLoginUser();
        log.warn("[权限拦截] 拒绝访问 user={} 角色={} uri={}",
                loginUser == null ? null : loginUser.getUsername(),
                loginUser == null ? null : loginUser.getRoleCode(),
                request.getRequestURI());
        write(response, 403, "权限不足");
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(Result.error(status, message)));
    }

    /**
     * 以 @Bean 注册（不加 @Component），避免 Boot 再把它当普通 servlet filter 全局注册一份
     */
    @Bean
    public TokenAuthFilter tokenAuthFilter() {
        return new TokenAuthFilter(jwtUtil, userMapper, roleMapper, permissionService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthFilter tokenAuthFilter) throws Exception {
        http
                // CORS 与 WebConfig 的 MVC 跨域配置共用一套口径（预检请求由此放行）
                .cors(Customizer.withDefaults())
                // 无状态令牌鉴权：禁 CSRF、不建会话
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecurityConfig::unauthorized)
                        .accessDeniedHandler(SecurityConfig::forbidden))
                .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
