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
 * <p>对标 HIS（cslk）的 {@code CurrentUser}：把「用户ID / 真实姓名 / 角色 / 生效权限」整合成
 * 唯一对象装进 {@code SecurityContextHolder}，业务侧用 {@link com.csssl.util.UserUtils#getLoginUser()} 取。
 *
 * <p><b>关键设计铁律：本类不暴露登录账号（username）</b>。账号是凭证，禁止写入任何业务表、
 * 禁止下发给业务接口出参、禁止在业务代码间传递——只有登录鉴权链路（TokenAuthFilter + JwtUtil）
 * 内部可接触账号，出了过滤器一律以 user_id + 真实姓名快照运作。</p>
 */
@Getter
public class LoginUser implements UserDetails {

    /**
     * 用户ID（{@code ssl_sys_user.id}）：业务表的操作人一律存它
     */
    private final Long userId;

    /**
     * 真实姓名（落库操作人姓名列的口径；取自拦截器回查到的用户行，非前端可伪造）
     */
    private final String realName;

    /**
     * 当前生效角色编码（库内当前值，非令牌快照）
     */
    private final String roleCode;

    /**
     * 是否法定内置角色（builtin=1）
     */
    private final boolean builtin;

    /**
     * 生效权限点集合（配置 ∩ 法定上限）
     */
    private final List<String> permissions;

    public LoginUser(Long userId, String realName, String roleCode,
                     boolean builtin, List<String> permissions) {
        this.userId = userId;
        this.realName = realName;
        this.roleCode = (roleCode == null || roleCode.isBlank()) ? null : roleCode.trim().toUpperCase();
        this.builtin = builtin;
        this.permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    @Override
    public String getUsername() {
        // 刻意返回 null：账号不在业务上下文暴露
        return null;
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
