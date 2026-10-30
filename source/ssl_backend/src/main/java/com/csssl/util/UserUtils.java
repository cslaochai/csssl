package com.csssl.util;

import com.csssl.entity.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全工具类 — 获取当前登录用户的唯一入口（HIS cslk 同款 {@code UserUtils} 的 csssl 版）。
 *
 * <p>所有「当前登录人」信息统一从 {@link SecurityContextHolder} 取（TokenAuthFilter 写入），
 * 非Web上下文（定时任务、异步线程）只要正确绑定了 SecurityContext 即可取到。
 * 调用方拿到 {@link LoginUser} 后自行取字段（username/realName/roleCode/tenantKey），
 * 本类不再提供逐字段便捷方法。</p>
 */
public class UserUtils {

    /** 当前登录人完整身份；未登录 / 上下文未绑定返回 null */
    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser u) {
            return u;
        }
        return null;
    }

    /** 当前登录人；未登录直接抛出（控制器/服务入口统一判空口径） */
    public static LoginUser requireLoginUser() {
        LoginUser u = getLoginUser();
        if (u == null) {
            throw new IllegalArgumentException("未能检测到登录用户");
        }
        return u;
    }
}
