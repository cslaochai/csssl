package com.csssl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
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
 * 拦截成功后把登录用户名与角色写入 request attribute（审计/权限校验用）。
 * 角色细分由各接口用 RoleCode.require 二次校验（GM/T 0034 §8.1.3 多级管理员分权）。
 *
 * <p>会话吊销：令牌合法后回查 ssl_sys_user —— 账号停用（status!=1）或令牌 tv 与库中
 * token_version 不符（改密/改角色/停用后版本+1）一律 401，旧令牌即刻不可用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USERNAME = "ssl.username";
    public static final String ATTR_ROLE = "ssl.role";
    /** 登录人机构数据域（权威值，回库取得）；全局用户该属性为 null */
    public static final String ATTR_ORG_KEY = "ssl.orgKey";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        JwtUtil.TokenClaims claims = auth != null && auth.startsWith("Bearer ")
                ? jwtUtil.parse(auth.substring(7)) : null;
        if (claims == null || claims.username() == null || claims.username().isBlank()) {
            return reject(request, response, 401, "未登录或登录已过期");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, claims.username()));
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return reject(request, response, 401, "账号不可用，请重新登录");
        }
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (claims.tokenVersion() == null || claims.tokenVersion() != currentVersion) {
            log.info("[会话] 令牌版本不符，已吊销 user={} tokenTv={} dbTv={}",
                    user.getUsername(), claims.tokenVersion(), currentVersion);
            return reject(request, response, 401, "登录状态已失效，请重新登录");
        }
        // 角色停用即整类账号会话失效（角色配置页可启停角色，即时收口，不等令牌自然过期）
        SysRole role = user.getRoleCode() == null || user.getRoleCode().isBlank() ? null
                : roleMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, user.getRoleCode().trim().toUpperCase()));
        if (role != null && (role.getStatus() == null || role.getStatus() != 1)) {
            log.info("[会话] 角色已停用，拒绝 user={} role={}", user.getUsername(), user.getRoleCode());
            return reject(request, response, 401, "账号所属角色已停用，请联系系统管理员");
        }
        request.setAttribute(ATTR_USERNAME, user.getUsername());
        // 角色以库中当前值为准（令牌角色仅作签发时快照）
        request.setAttribute(ATTR_ROLE, user.getRoleCode());
        // 机构数据域以库中当前值为准：机构用户=本人机构；全局用户=NullAttribute（各控制器按 effectiveOrg 口径处理）
        if (user.getOrgKey() != null && !user.getOrgKey().isBlank()) {
            request.setAttribute(ATTR_ORG_KEY, user.getOrgKey());
        }
        return true;
    }

    private boolean reject(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(Result.error(status, message)));
        return false;
    }
}
