package com.csssl.enums;

import com.csssl.util.PermissionCatalog;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 预置角色枚举（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡；GB/T 25056-2018 §10.1 八类法定角色）。
 *
 * <p>枚举是"预置角色目录"的单一来源：每个常量携带展示名、职责、排序、是否法定内置，
 * 以及 {@link #defaultMax()}（该角色的法定权限上限）。运行期授权上限以本枚举为准——
 * 预置角色只能在其上限内由管理员收窄，不能越界（守住签发≠审核等分权制衡）。</p>
 *
 * <p>自定义角色（管理员在后台自建，{@code builtin=0}）不在此枚举内，仅存于数据库；
 * 其授权上限为全量权限目录（见 {@code PermissionCatalog.statutoryMax}），由配置方自行组合。</p>
 *
 * <p>角色模型：11 个预置角色 = 六类法定核心角色（ADMIN/OPERATOR/APPROVER/AUDITOR/SECURITY_ADMIN/KEY_ADMIN）
 * + 按 GB/T 25056 §10.1 八类职责拆出的运营子角色（TENANT_ADMIN/USER_ADMIN/RA_ADMIN/CERT_OFFICER/AUDIT_ADMIN）；
 * 另支持后台自建自定义角色（builtin=0）。</p>
 * <ul>
 *   <li>ADMIN          系统管理员：用户/角色/租户档案等人工信息维护，业务台账只读；签发/审核/吊销/模板/根密钥等业务操作不沾（三员分立）</li>
 *   <li>OPERATOR       CA操作员：录入证书申请、执行签发（签发与审核分离）</li>
 *   <li>APPROVER       审核员：审核证书申请（不得与录入同一人）</li>
 *   <li>AUDITOR        审计员：仅可查看审计与证书，不可签发/审核，独立于业务操作</li>
 *   <li>SECURITY_ADMIN 安全管理员：证书模板/证书策略（KeyUsage/EKU/策略OID）管理</li>
 *   <li>KEY_ADMIN      密钥管理员：根密钥门限分持/封存/解封（份额保管与恢复）</li>
 *   <li>TENANT_ADMIN      租户管理员：多租户域（独立根CA）的创建/启停/恢复/关闭</li>
 *   <li>USER_ADMIN     用户与角色管理员：账号与角色档案维护（建号、改角色、启停、授权）</li>
 *   <li>RA_ADMIN       RA业务管理员：RA 操作员账号与审核流程配置（与录入分离）</li>
 *   <li>CERT_OFFICER   证书签发操作员：仅执行证书签发/吊销/挂起/更新/换密钥/下载（不含申请录入）</li>
 *   <li>AUDIT_ADMIN    审计管理员：管理审计员账号与审计策略配置，独立于业务操作</li>
 * </ul>
 */
@Getter
public enum RoleCodeEnum {

    ADMIN("ADMIN", "系统管理员", "用户/角色/租户档案等人工信息维护，业务台账只读；签发/审核/吊销/模板/根密钥等业务操作一律不沾（三员分立）", 10, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CERTS,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.MENU_TENANTS, PermissionCatalog.MENU_USERS, PermissionCatalog.MENU_ROLES,
            PermissionCatalog.CERT_LIST, PermissionCatalog.CERT_DOWNLOAD, PermissionCatalog.CRL_DOWNLOAD,
            PermissionCatalog.TENANT_CREATE, PermissionCatalog.TENANT_SUSPEND, PermissionCatalog.TENANT_RESUME,
            PermissionCatalog.TENANT_CLOSE,
            PermissionCatalog.USER_LIST, PermissionCatalog.USER_CREATE, PermissionCatalog.USER_UPDATE,
            PermissionCatalog.ROLE_UPDATE, PermissionCatalog.ROLE_PERM)),

    OPERATOR("OPERATOR", "CA操作员", "证书申请录入、签发、吊销/挂起/更新；签发与审核分离", 20, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CERTS,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.APP_SUBMIT, PermissionCatalog.APP_ISSUE, PermissionCatalog.APP_CANCEL,
            PermissionCatalog.CERT_LIST, PermissionCatalog.CERT_ISSUE, PermissionCatalog.CERT_REVOKE,
            PermissionCatalog.CERT_SUSPEND, PermissionCatalog.CERT_RENEW, PermissionCatalog.CERT_REKEY,
            PermissionCatalog.CERT_DOWNLOAD, PermissionCatalog.CRL_DOWNLOAD)),

    APPROVER("APPROVER", "审核员", "审核证书申请与证书操作单（吊销/挂起复核）；不得与发起人同一人", 30, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CERTS,
            PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.APP_APPROVE, PermissionCatalog.APP_REJECT,
            PermissionCatalog.CERT_LIST, PermissionCatalog.CERT_OP_APPROVE)),

    AUDITOR("AUDITOR", "审计员", "审计日志查询/验签/归档；只读，不可签发/审核，独立于业务操作", 40, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP,
            PermissionCatalog.MENU_TSA, PermissionCatalog.MENU_AUDIT,
            PermissionCatalog.AUDIT_VIEW, PermissionCatalog.AUDIT_VERIFY, PermissionCatalog.AUDIT_CHAIN,
            PermissionCatalog.AUDIT_EXPORT)),

    SECURITY_ADMIN("SECURITY_ADMIN", "安全管理员", "证书模板与证书策略（KeyUsage/EKU/策略OID）管理", 50, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_TEMPLATES, PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.TEMPLATE_CREATE, PermissionCatalog.TEMPLATE_UPDATE, PermissionCatalog.TEMPLATE_DELETE)),

    KEY_ADMIN("KEY_ADMIN", "密钥管理员", "根密钥门限分持/封存/解封与份额保管", 60, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_CERTS, PermissionCatalog.MENU_ROOT_CA,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.CERT_LIST, PermissionCatalog.CERT_KEY_DOWNLOAD, PermissionCatalog.CERT_EXPORT,
            PermissionCatalog.CA_INIT, PermissionCatalog.CA_SEAL, PermissionCatalog.CA_UNSEAL,
            PermissionCatalog.CA_SHARE_VIEW, PermissionCatalog.CA_SHARE_DOWNLOAD, PermissionCatalog.CA_SHARE_MIGRATE,
            PermissionCatalog.CA_SHARE_UNSEAL, PermissionCatalog.CA_ROOT_DOWNLOAD, PermissionCatalog.CA_SUBCA_DOWNLOAD,
            PermissionCatalog.CRL_DOWNLOAD)),

    TENANT_ADMIN("TENANT_ADMIN", "租户管理员", "多租户域（独立根CA）的创建/启停/恢复/关闭", 70, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_TENANTS, PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.TENANT_CREATE, PermissionCatalog.TENANT_SUSPEND, PermissionCatalog.TENANT_RESUME,
            PermissionCatalog.TENANT_CLOSE)),

    USER_ADMIN("USER_ADMIN", "用户与角色管理员", "管理账号与角色档案（建号、改角色、启停、重置口令、角色授权）", 80, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_USERS, PermissionCatalog.MENU_ROLES,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.USER_LIST, PermissionCatalog.USER_CREATE, PermissionCatalog.USER_UPDATE,
            PermissionCatalog.ROLE_UPDATE, PermissionCatalog.ROLE_PERM)),

    RA_ADMIN("RA_ADMIN", "RA业务管理员", "管理 RA 操作员账号与审核流程配置（审核/驳回/撤销），与录入分离", 90, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.APP_APPROVE, PermissionCatalog.APP_REJECT, PermissionCatalog.APP_CANCEL,
            PermissionCatalog.USER_LIST, PermissionCatalog.USER_CREATE, PermissionCatalog.USER_UPDATE)),

    CERT_OFFICER("CERT_OFFICER", "证书签发操作员", "仅执行证书签发/吊销/挂起/更新/换密钥/下载（不含申请录入）", 100, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_CERTS, PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.CERT_LIST, PermissionCatalog.CERT_ISSUE, PermissionCatalog.CERT_REVOKE,
            PermissionCatalog.CERT_SUSPEND, PermissionCatalog.CERT_RENEW, PermissionCatalog.CERT_REKEY,
            PermissionCatalog.CERT_DOWNLOAD, PermissionCatalog.CRL_DOWNLOAD)),

    AUDIT_ADMIN("AUDIT_ADMIN", "审计管理员", "管理审计员账号与审计策略配置，独立于业务操作", 110, true, set(
            PermissionCatalog.MENU_DASHBOARD, PermissionCatalog.MENU_AUDIT, PermissionCatalog.MENU_USERS,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.AUDIT_VIEW, PermissionCatalog.AUDIT_VERIFY, PermissionCatalog.AUDIT_CHAIN,
            PermissionCatalog.AUDIT_EXPORT, PermissionCatalog.USER_LIST, PermissionCatalog.USER_CREATE,
            PermissionCatalog.USER_UPDATE));

    private final String code;
    private final String roleName;
    private final String roleDesc;
    private final int sortOrder;
    private final boolean builtin;
    private final Set<String> defaultMax;

    RoleCodeEnum(String code, String roleName, String roleDesc, int sortOrder, boolean builtin, Set<String> defaultMax) {
        this.code = code;
        this.roleName = roleName;
        this.roleDesc = roleDesc;
        this.sortOrder = sortOrder;
        this.builtin = builtin;
        this.defaultMax = Collections.unmodifiableSet(defaultMax);
    }

    /**
     * 角色的法定权限上限（预置角色不可越界）
     */
    public Set<String> defaultMax() {
        return defaultMax;
    }

    /**
     * 由角色编码取枚举（忽略大小写/空白），未知返回 null
     */
    public static RoleCodeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (RoleCodeEnum r : values()) {
            if (r.code.equals(c)) {
                return r;
            }
        }
        return null;
    }

    private static Set<String> set(String... codes) {
        Set<String> s = new LinkedHashSet<>();
        Collections.addAll(s, codes);
        return Collections.unmodifiableSet(s);
    }
}
