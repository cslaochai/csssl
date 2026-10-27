package com.csssl.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管理角色与接口权限校验（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 *
 * <p>角色模型（六类角色，对应经办/监管/密钥/策略分离逻辑，§8.1.3）：
 * <ul>
 *   <li>ADMIN         系统管理员：用户/角色管理、全量兜底（含全部操作权限）</li>
 *   <li>OPERATOR      CA操作员：录入证书申请、执行签发（签发与审核分离）</li>
 *   <li>APPROVER      审核员：审核证书申请（不得与录入同一人，§10.1g）</li>
 *   <li>AUDITOR       审计员：仅可查看审计与证书，不可签发/审核/管理</li>
 *   <li>SECURITY_ADMIN 安全管理员：证书模板/证书策略管理（KeyUsage/EKU/策略OID）</li>
 *   <li>KEY_ADMIN     密钥管理员：根密钥门限分持/封存/解封（份额保管与恢复）</li>
 * </ul>
 */
public final class RoleCode {

    /** 系统管理员 */
    public static final String ADMIN = "ADMIN";
    /** CA操作员（录入/签发） */
    public static final String OPERATOR = "OPERATOR";
    /** 审核员（审批申请） */
    public static final String APPROVER = "APPROVER";
    /** 审计员（只读） */
    public static final String AUDITOR = "AUDITOR";
    /** 安全管理员（证书模板/策略管理） */
    public static final String SECURITY_ADMIN = "SECURITY_ADMIN";
    /** 密钥管理员（根密钥门限分持/封存/解封） */
    public static final String KEY_ADMIN = "KEY_ADMIN";

    private RoleCode() {
    }

    /** 取当前请求登录人角色（登录拦截器写入；未登录返回 null） */
    public static String currentRole(HttpServletRequest request) {
        return (String) request.getAttribute("ssl.role");
    }

    /** 当前登录人用户名（登录拦截器写入） */
    public static String currentUser(HttpServletRequest request) {
        return (String) request.getAttribute("ssl.username");
    }

    /**
     * 校验当前登录人是否具备任一角色；不具备抛 {@link SecurityException}（全局转 403）。
     * 同时校验已登录（用户名非空）。
     */
    public static void require(HttpServletRequest request, String... roles) {
        String user = currentUser(request);
        if (user == null || user.isBlank()) {
            throw new SecurityException("未登录或登录已过期");
        }
        String role = currentRole(request);
        if (roles == null || roles.length == 0) {
            return;
        }
        for (String r : roles) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new SecurityException("权限不足：该操作需要角色 " + String.join("/", roles) + "（当前角色：" + (role == null ? "无" : role) + "）");
    }
}
