package com.csssl.entity;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 当前登录人身份载体，同时是 {@code SecurityContextHolder} 里 {@code Authentication#getPrincipal()} 的类型。
 *
 * <p>对标 HIS（cslk）的 {@code CurrentUser}：把「用户名 / 真实姓名 / 角色 / 租户数据域 / 生效权限」整合成
 * 唯一对象装进 {@code SecurityContextHolder}，业务侧用 {@link com.csssl.util.UserUtils#getLoginUser()} 取，
 * 不再散落依赖 {@code HttpServletRequest} 属性。这样：</p>
 * <ul>
 *   <li>与前端解耦——身份由 TokenAuthFilter 在校验 JWT 签名 + 回查库后写入，前端无法伪造；</li>
 *   <li>可在任意线程/异步上下文（定时任务、@Async、服务层）拿到登录人，不再强依赖 request；</li>
 *   <li>{@code getAuthorities()} 含 {@code ROLE_<role>} 与各权限点，既驱动 {@code @PreAuthorize(hasAuthority)}，
 *       也支持未来 {@code hasRole}。</li>
 * </ul>
 */
@Getter
public class LoginUser implements UserDetails {

    /**
     * 登录账号
     */
    private final String username;

    /**
     * 真实姓名（落库操作人列口径；令牌快照，登录时写入）
     */
    private final String realName;

    /**
     * 当前生效角色编码（库内当前值，非令牌快照）
     */
    private final String roleCode;

    /**
     * 租户数据域：null/空=全局用户（跨租户账号）；非空=租户用户，一律锁定本租户
     */
    private final String tenantKey;

    /**
     * 是否法定内置角色（builtin=1）
     */
    private final boolean builtin;

    /**
     * 生效权限点集合（配置 ∩ 法定上限）
     */
    private final List<String> permissions;

    public LoginUser(String username, String realName, String roleCode, String tenantKey,
                     boolean builtin, List<String> permissions) {
        this.username = username;
        this.realName = realName;
        this.roleCode = (roleCode == null || roleCode.isBlank()) ? null : roleCode.trim().toUpperCase();
        this.tenantKey = (tenantKey == null || tenantKey.isBlank()) ? null : tenantKey.trim();
        this.builtin = builtin;
        this.permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Collection<GrantedAuthority> auths = new ArrayList<>();
        if (roleCode != null) {
            auths.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
        }
        for (String p : permissions) {
            if (p != null && !p.isBlank()) {
                auths.add(new SimpleGrantedAuthority(p));
            }
        }
        return auths;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
