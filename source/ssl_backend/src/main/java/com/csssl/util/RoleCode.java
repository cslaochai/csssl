package com.csssl.util;

import com.csssl.entity.CaOrg;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
     * 当前登录人机构数据域（登录拦截器回库写入 ssl.orgKey）。
     * 返回 null=全局用户（org_key 为空，跨机构账号）；非空=机构用户，一律锁定本机构。
     */
    public static String currentOrg(HttpServletRequest request) {
        Object v = request.getAttribute("ssl.orgKey");
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 机构数据域统一口径（effectiveOrg，所有机构域操作唯一入口，安全红线不设旁路）：
     * <ul>
     *   <li>机构用户（本人 org_key 非空）→ 一律用本机构；请求显式 orgKey 与本人机构不一致直接 403；</li>
     *   <li>全局用户（org_key 为空）→ 用请求显式 orgKey，缺省 default 域。</li>
     * </ul>
     * 即：不存在"全部机构"的写旁路，跨机构写必须逐机构显式传参且缺省只落 default。
     */
    public static String effectiveOrg(HttpServletRequest request, String reqOrgKey) {
        return effectiveOrg(currentOrg(request), reqOrgKey);
    }

    /** effectiveOrg 的无请求对象版本（供服务层单测/复用） */
    public static String effectiveOrg(String operatorOrg, String reqOrgKey) {
        String req = reqOrgKey == null || reqOrgKey.isBlank() ? null : reqOrgKey.trim();
        if (operatorOrg != null && !operatorOrg.isBlank()) {
            if (req != null && !req.equals(operatorOrg)) {
                log.warn("[数据域] 机构用户 {} 越域访问机构 {}，拒绝", operatorOrg, req);
                throw new SecurityException("权限不足：不能操作其他机构数据");
            }
            return operatorOrg;
        }
        return req == null ? CaOrg.PRIMARY_ORG_KEY : req;
    }

    /**
     * 机构数据域"读"口径（列表/查询类端点）：
     * 机构用户强制本机构过滤（显式传别机构 403）；全局用户传 orgKey=按机构过滤，
     * 不传=null 表示跨机构全量只读——这是唯一允许的跨机构读口（监督/总览职能，只读放行）。
     */
    public static String readOrgScope(HttpServletRequest request, String reqOrgKey) {
        String myOrg = currentOrg(request);
        if (myOrg != null && !myOrg.isBlank()) {
            return effectiveOrg(myOrg, reqOrgKey);
        }
        return reqOrgKey == null || reqOrgKey.isBlank() ? null : reqOrgKey.trim();
    }

    /**
     * 行级"读"校验：机构用户仅可访问本机构数据行（不属本机构→403）；
     * 全局用户放行（跨机构只读为总览/监督职能的合规设计）。rowOrg 空按 default 归位。
     */
    public static void requireRowVisible(HttpServletRequest request, String rowOrgKey) {
        requireRowVisible(currentOrg(request), rowOrgKey);
    }

    /** requireRowVisible 的无请求对象版本（服务层复用同一口径） */
    public static void requireRowVisible(String operatorOrg, String rowOrgKey) {
        if (operatorOrg == null || operatorOrg.isBlank()) {
            return;
        }
        String row = rowOrgKey == null || rowOrgKey.isBlank() ? CaOrg.PRIMARY_ORG_KEY : rowOrgKey;
        if (!operatorOrg.equals(row)) {
            log.warn("[数据域] 机构用户越域读取数据行（机构 {}），拒绝", row);
            throw new SecurityException("权限不足：不能访问其他机构数据");
        }
    }

    /**
     * 行级"写"校验：操作必须落在操作者数据域（effectiveOrg 与数据行归属机构一致，否则 403）。
     * 机构用户经 effectiveOrg 天然锁本机构；全局用户缺省只落 default，写他机构必须显式传 orgKey。
     */
    public static String requireWriteOrg(HttpServletRequest request, String reqOrgKey, String rowOrgKey) {
        String eff = effectiveOrg(request, reqOrgKey);
        String row = rowOrgKey == null || rowOrgKey.isBlank() ? CaOrg.PRIMARY_ORG_KEY : rowOrgKey;
        if (!eff.equals(row)) {
            log.warn("[数据域] 操作域 {} 与数据行归属机构 {} 不一致，拒绝", eff, row);
            throw new SecurityException("权限不足：不能操作其他机构数据");
        }
        return eff;
    }

    /** 数据行归属机构兜底：org_key 列 NOT NULL DEFAULT 'default'，历史语义空=默认域 */
    public static String orgOfRow(String rowOrgKey) {
        return rowOrgKey == null || rowOrgKey.isBlank() ? CaOrg.PRIMARY_ORG_KEY : rowOrgKey;
    }

    /**
     * 校验当前登录人是否具备任一角色；不具备抛 {@link SecurityException}（全局转 403）。
     * 同时校验已登录（用户名非空）。
     *
     * <p>拒绝原因（所需角色/当前角色）只写日志，异常文案泛化为"权限不足"，
     * 防止未授权调用方据此探测权限矩阵。</p>
     */
    public static void require(HttpServletRequest request, String... roles) {
        String user = currentUser(request);
        if (user == null || user.isBlank()) {
            log.warn("[权限] 未登录访问受限接口 uri={}", request.getRequestURI());
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
        log.warn("[权限] 拒绝访问 user={} 当前角色={} 所需角色={} uri={}",
                user, role == null ? "无" : role, String.join("/", roles), request.getRequestURI());
        throw new SecurityException("权限不足");
    }
}
