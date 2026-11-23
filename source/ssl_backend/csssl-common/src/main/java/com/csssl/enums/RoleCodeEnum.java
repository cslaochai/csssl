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
 * <p>角色模型：10 个预置角色 = 六类法定核心角色（ADMIN/OPERATOR/APPROVER/AUDITOR/SECURITY_ADMIN/KEY_ADMIN）
 * + 按 GB/T 25056 §10.1 八类职责拆出的运营子角色（COMPANY_ADMIN/USER_ADMIN/RA_ADMIN/AUDIT_ADMIN）；
 * 另支持后台自建自定义角色（builtin=0）。</p>
 * <ul>
 *   <li>ADMIN          系统管理员：账号建号与档案维护；全菜单只读巡检（证书/申请/公司/模板/审计只查看），签发/审核/吊销/启停公司/角色授权等操作不沾（三员分立）</li>
 *   <li>OPERATOR       CA操作员：录入证书申请、执行签发（签发与审核分离）</li>
 *   <li>APPROVER       审核员：审核证书申请（不得与录入同一人）</li>
 *   <li>AUDITOR        审计员：仅可查看审计与证书，不可签发/审核，独立于业务操作</li>
 *   <li>SECURITY_ADMIN 安全管理员：证书模板/证书策略（KeyUsage/EKU/策略OID）管理；平台侧承接二级CA重建的发起与执行</li>
 *   <li>KEY_ADMIN      密钥管理员：根密钥门限分持/封存/解封（份额保管与恢复）；在审批中心投票并交出本人份额，不参与发起与执行</li>
 *   <li>COMPANY_ADMIN      公司管理员：新建公司直接执行（开公司即建根），发起暂停/恢复/关闭/接入密钥重置变更单，API 客户端登记与轮换；本公司二级CA重建的发起与执行</li>
 *   <li>COMPANY_APPROVER   公司审批员：审批公司变更单（不得与发起人同一人），不参与发起</li>
 *   <li>USER_ADMIN     用户与角色管理员：账号与角色档案维护（建号、改角色、启停、授权）</li>
 *   <li>RA_ADMIN       RA业务管理员：RA 操作员账号与审核流程配置（与录入分离）</li>
 *   <li>AUDIT_ADMIN    审计管理员：管理审计员账号与审计策略配置，独立于业务操作</li>
 * </ul>
 */
@Getter
public enum RoleCodeEnum {

    ADMIN("ADMIN", "系统管理员", "全菜单只读巡检（证书/申请/公司/模板/审计/用户只查看与检索），签发/审核/吊销/启停公司/建号/角色授权等操作一律不沾", 10, true, set(
            PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CERTS,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.MENU_COMPANIES, PermissionCatalog.MENU_COMPANY_OPS,
            PermissionCatalog.MENU_ROOT_CA, PermissionCatalog.MENU_TEMPLATES,
            PermissionCatalog.MENU_USERS, PermissionCatalog.MENU_ROLES, PermissionCatalog.MENU_AUDIT,
            PermissionCatalog.MENU_MY_APPROVALS,
            PermissionCatalog.CERT_LIST, PermissionCatalog.USER_LIST, PermissionCatalog.AUDIT_VIEW,
            PermissionCatalog.CA_SHARE_VIEW,
            PermissionCatalog.CA_SUBCA_REBUILD_VIEW,
            PermissionCatalog.APP_LIST_APPROVERS,
            PermissionCatalog.MENU_WORKFLOW)),

    OPERATOR("OPERATOR", "CA操作员", "证书申请录入、对已批准申请执行签发、发起吊销/挂起/更新操作单；签发与审核分离", 20, true, set(
            PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CERTS,
            PermissionCatalog.MENU_CRL, PermissionCatalog.MENU_OCSP,             PermissionCatalog.MENU_TSA,
            PermissionCatalog.APP_SUBMIT, PermissionCatalog.APP_ISSUE, PermissionCatalog.APP_CANCEL,
            PermissionCatalog.CERT_LIST, PermissionCatalog.CERT_REVOKE,
            PermissionCatalog.CERT_SUSPEND, PermissionCatalog.CERT_RENEW, PermissionCatalog.CERT_REKEY,
            PermissionCatalog.CERT_DOWNLOAD, PermissionCatalog.CRL_DOWNLOAD,
            PermissionCatalog.APP_URGE, PermissionCatalog.APP_LIST_APPROVERS,
            PermissionCatalog.MENU_MY_APPROVALS)),

    APPROVER("APPROVER", "审核员", "审核证书申请与证书操作单（吊销/挂起复核）；不得与发起人同一人。菜单只留概览+我的待办，证书/CRL/OCSP/TSA/RA申请等业务台账一律不进", 30, true, set(
            PermissionCatalog.MENU_MY_APPROVALS,
            PermissionCatalog.APP_APPROVE, PermissionCatalog.APP_REJECT,
            PermissionCatalog.CERT_OP_APPROVE)),

    AUDITOR("AUDITOR", "审计员", "审计日志查询/验签/归档；只读，不可签发/审核，独立于业务操作。只看审计日志，不进业务台账", 40, true, set(
            PermissionCatalog.MENU_AUDIT,
            PermissionCatalog.AUDIT_VIEW, PermissionCatalog.AUDIT_VERIFY, PermissionCatalog.AUDIT_CHAIN,
            PermissionCatalog.AUDIT_EXPORT)),

    SECURITY_ADMIN("SECURITY_ADMIN", "安全管理员", "证书模板与证书策略（KeyUsage/EKU/策略OID）管理；二级CA重建申请的发起与执行（平台侧应急通道）；审批流程可视化编排", 50, true, set(
            PermissionCatalog.MENU_TEMPLATES, PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.MENU_MY_APPROVALS, PermissionCatalog.MENU_WORKFLOW,
            PermissionCatalog.TEMPLATE_CREATE, PermissionCatalog.TEMPLATE_UPDATE, PermissionCatalog.TEMPLATE_DELETE,
            PermissionCatalog.WORKFLOW_EDIT,
            PermissionCatalog.CA_SUBCA_REBUILD_VIEW, PermissionCatalog.CA_SUBCA_REBUILD_APPLY,
            PermissionCatalog.CA_SUBCA_REBUILD_EXECUTE)),

    KEY_ADMIN("KEY_ADMIN", "密钥管理员", "根密钥门限分持/封存/解封与份额保管。菜单只留我的待办——投票/贴份额/解封全在待办里内联完成，不看证书台账与平台管理", 60, true, set(
            PermissionCatalog.MENU_MY_APPROVALS,
            PermissionCatalog.CA_INIT, PermissionCatalog.CA_SEAL, PermissionCatalog.CA_UNSEAL,
            PermissionCatalog.CA_SHARE_VIEW, PermissionCatalog.CA_SHARE_DOWNLOAD, PermissionCatalog.CA_SHARE_MIGRATE,
            PermissionCatalog.CA_SHARE_UNSEAL, PermissionCatalog.CA_ROOT_DOWNLOAD, PermissionCatalog.CA_SUBCA_DOWNLOAD,
            PermissionCatalog.CA_SUBCA_REBUILD_VIEW,
            PermissionCatalog.CERT_OP_APPROVE)),

    COMPANY_ADMIN("COMPANY_ADMIN", "公司管理员", "新建公司直接执行（开公司即建独立根CA+SUB_CA、发接入凭证）；发起暂停/恢复/关闭/重置接入密钥及重新指派保管人变更单，系统接入与凭证轮换。二级CA重建在公司详情内发起，不单列菜单", 70, true, set(
            PermissionCatalog.MENU_COMPANIES, PermissionCatalog.MENU_COMPANY_OPS,
            PermissionCatalog.COMPANY_CREATE, PermissionCatalog.COMPANY_OP_APPLY,
            PermissionCatalog.APICLIENT_MANAGE,
            PermissionCatalog.CA_SHARE_VIEW, PermissionCatalog.CA_SHARE_ASSIGN, PermissionCatalog.CA_SUBCA_REBUILD_VIEW,
            PermissionCatalog.CA_SUBCA_REBUILD_APPLY,
            PermissionCatalog.CA_SUBCA_REBUILD_EXECUTE, PermissionCatalog.MENU_MY_APPROVALS)),

    COMPANY_APPROVER("COMPANY_APPROVER", "公司审批员", "审批公司变更单（暂停/恢复/关闭/接入密钥重置），批准即执行；不得与发起人同一人", 75, true, set(
            PermissionCatalog.MENU_COMPANIES, PermissionCatalog.MENU_COMPANY_OPS,
            PermissionCatalog.COMPANY_OP_APPROVE,
            PermissionCatalog.MENU_MY_APPROVALS)),

    USER_ADMIN("USER_ADMIN", "平台管理员", "管理账号与角色档案（建号、改角色、启停、重置口令、角色授权）。只看用户/角色两个菜单+消息待办，不进业务台账", 80, true, set(
            PermissionCatalog.MENU_USERS, PermissionCatalog.MENU_ROLES,
            PermissionCatalog.USER_LIST, PermissionCatalog.USER_CREATE, PermissionCatalog.USER_UPDATE,
            PermissionCatalog.ROLE_UPDATE, PermissionCatalog.ROLE_PERM)),

    RA_ADMIN("RA_ADMIN", "RA业务管理员", "管理 RA 操作员账号与审核流程配置（审核/驳回/撤销），与录入分离", 90, true, set(
            PermissionCatalog.MENU_APPLICATIONS, PermissionCatalog.MENU_CRL,
            PermissionCatalog.MENU_OCSP, PermissionCatalog.MENU_TSA,
            PermissionCatalog.APP_APPROVE, PermissionCatalog.APP_REJECT, PermissionCatalog.APP_CANCEL,
            PermissionCatalog.USER_LIST, PermissionCatalog.USER_CREATE, PermissionCatalog.USER_UPDATE,
            PermissionCatalog.APP_URGE, PermissionCatalog.APP_LIST_APPROVERS,
            PermissionCatalog.MENU_MY_APPROVALS)),

    AUDIT_ADMIN("AUDIT_ADMIN", "审计管理员", "管理审计员账号与审计策略配置，独立于业务操作。只看审计日志+审计员账号管理，不进业务台账", 110, true, set(
            PermissionCatalog.MENU_AUDIT, PermissionCatalog.MENU_USERS,
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
