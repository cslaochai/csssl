package com.csssl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.Result;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.PermissionService;
import com.csssl.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 管理接口 JWT 认证过滤器（Spring Security 过滤器链一环，仅作用于 /admin/**）：
 * 解析 Authorization: Bearer <token>，回查 ssl_sys_user 与令牌版本，通过后
 * 组装带权限点 authorities 的 Authentication 写入 SecurityContextHolder（身份载体 LoginUser）
 * ——@PreAuthorize("hasAuthority('…')") 即以此判定，业务侧经 UserUtils 取当前登录人。
 *
 * <p>会话吊销：令牌合法后回查 ssl_sys_user —— 账号停用（status!=1）或令牌 tv 与库中
 * token_version 不符（改密/改角色/停用后版本+1）一律 401，旧令牌即刻不可用；
 * 角色停用同样即时 401。权限点集合 = 角色生效权限（配置 ∩ 法定上限，PermissionService）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PermissionService permissionService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只接管 /admin/**；公开接口（/auth、/tsa、/ca/public）不进入本过滤器
        return !request.getServletPath().startsWith("/admin")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        JwtUtil.TokenClaims claims = auth != null && auth.startsWith("Bearer ")
                ? jwtUtil.parse(auth.substring(7)) : null;
        if (claims == null || claims.username() == null || claims.username().isBlank()) {
            reject(response, 401, "未登录或登录已过期");
            return;
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, claims.username()));
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            reject(response, 401, "账号不可用，请重新登录");
            return;
        }
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (claims.tokenVersion() == null || claims.tokenVersion() != currentVersion) {
            log.info("[会话] 令牌版本不符，已吊销 user={} tokenTv={} dbTv={}",
                    user.getUsername(), claims.tokenVersion(), currentVersion);
            reject(response, 401, "登录状态已失效，请重新登录");
            return;
        }
        // 角色停用即整类账号会话失效（角色配置页可启停角色，即时收口，不等令牌自然过期）
        SysRole role = user.getRoleCode() == null || user.getRoleCode().isBlank() ? null
                : roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, user.getRoleCode().trim().toUpperCase()));
        if (role != null && (role.getStatus() == null || role.getStatus() != 1)) {
            log.info("[会话] 角色已停用，拒绝 user={} role={}", user.getUsername(), user.getRoleCode());
            reject(response, 401, "账号所属角色已停用，请联系系统管理员");
            return;
        }
        // 生效权限点（配置 ∩ 法定上限），@PreAuthorize("hasAuthority('…')") 与前端按钮鉴权依此判定
        List<String> permCodes = permissionService.effectiveList(user.getRoleCode());
        // HIS 同款：把"当前登录人"整体装进 SecurityContextHolder（身份载体 LoginUser 实现 UserDetails），
        // 业务侧用 UserUtils.getLoginUser() 取；该身份由签名校验+回库后写入，前端无法伪造。
        // 真实姓名取自令牌快照（登录时写入）；角色与租户数据域以库中当前值为准。
        LoginUser loginUser = new LoginUser(
                user.getUsername(),
                claims.realName(),
                user.getRoleCode(),
                user.getTenantKey(),
                role != null && role.getBuiltin() != null && role.getBuiltin() == 1,
                permCodes);
        // getAuthorities() 已含 ROLE_<role> + 各权限点，既支持 hasAuthority 也支持未来 hasRole
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(Result.error(status, message)));
    }
}
