package com.csssl.util;

import com.csssl.enums.RoleCodeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限点目录与权限点类型（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li><b>权限点由代码定义、随代码发布</b>：目录与接口一一对应，不落库，避免配置漂移产生"幽灵权限"；</li>
 *   <li><b>角色法定上限内置</b>：预置角色的可授范围在 {@link RoleCodeEnum} 枚举中固化，角色配置页只能在上限内收窄；
 *       保存与读取都做"配置 ∩ 上限"，即使直接改库也不能越权（如给审核员勾签发）；</li>
 *   <li><b>两类权限点</b>：MENU 控制侧栏/路由进入，ACTION 控制按钮显隐与接口校验。</li>
 *   <li><b>自定义角色</b>：预置角色为默认（见 {@link RoleCodeEnum}），管理员可在后台自建角色，
 *       自定义角色的授权上限为全量权限目录（管理员自行组合，责任在配置方）。</li>
 * </ul>
 */
public final class PermissionCatalog {

    /** 权限点类型：页面菜单 */
    public static final String TYPE_MENU = "MENU";
    /** 权限点类型：操作按钮/接口 */
    public static final String TYPE_ACTION = "ACTION";

    // ===== 菜单权限点（与前端路由 meta.perm 一一对应） =====
    public static final String MENU_DASHBOARD = "menu:dashboard";
    public static final String MENU_APPLICATIONS = "menu:applications";
    public static final String MENU_CERTS = "menu:certs";
    public static final String MENU_CRL = "menu:crl";
    public static final String MENU_OCSP = "menu:ocsp";
    public static final String MENU_TSA = "menu:tsa";
    public static final String MENU_ORGS = "menu:orgs";
    public static final String MENU_ROOT_CA = "menu:root-ca";
    public static final String MENU_TEMPLATES = "menu:templates";
    public static final String MENU_USERS = "menu:users";
    public static final String MENU_ROLES = "menu:roles";
    public static final String MENU_AUDIT = "menu:audit";

    // ===== 操作权限点 =====
    public static final String APP_SUBMIT = "app:submit";
    public static final String APP_APPROVE = "app:approve";
    public static final String APP_REJECT = "app:reject";
    public static final String APP_ISSUE = "app:issue";
    public static final String APP_CANCEL = "app:cancel";

    public static final String CERT_LIST = "cert:list";
    public static final String CERT_ISSUE = "cert:issue";
    public static final String CERT_ISSUE_SUBCA = "cert:issue:subca";
    public static final String CERT_REVOKE = "cert:revoke";
    public static final String CERT_SUSPEND = "cert:suspend";
    public static final String CERT_RENEW = "cert:renew";
    public static final String CERT_REKEY = "cert:rekey";
    public static final String CERT_DOWNLOAD = "cert:download";
    public static final String CERT_KEY_DOWNLOAD = "cert:key:download";
    public static final String CERT_EXPORT = "cert:export";
    public static final String CRL_DOWNLOAD = "crl:download";

    public static final String TEMPLATE_CREATE = "template:create";
    public static final String TEMPLATE_UPDATE = "template:update";
    public static final String TEMPLATE_DELETE = "template:delete";

    public static final String CA_INIT = "ca:init";
    public static final String CA_SEAL = "ca:seal";
    public static final String CA_UNSEAL = "ca:unseal";
    public static final String CA_SHARE_VIEW = "ca:share:view";
    public static final String CA_SHARE_DOWNLOAD = "ca:share:download";
    public static final String CA_SHARE_MIGRATE = "ca:share:migrate";
    public static final String CA_SHARE_UNSEAL = "ca:share:unseal";
    public static final String CA_ROOT_DOWNLOAD = "ca:root:download";
    public static final String CA_SUBCA_DOWNLOAD = "ca:subca:download";

    public static final String ORG_CREATE = "org:create";
    public static final String ORG_SUSPEND = "org:suspend";
    public static final String ORG_RESUME = "org:resume";
    public static final String ORG_CLOSE = "org:close";

    public static final String USER_LIST = "user:list";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";

    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_PERM = "role:perm";

    public static final String AUDIT_VIEW = "audit:view";
    public static final String AUDIT_VERIFY = "audit:verify";
    public static final String AUDIT_CHAIN = "audit:chain";
    public static final String AUDIT_EXPORT = "audit:export";

    /** 权限点定义（顺序即前端权限树展示顺序） */
    public record PermDef(String code, String name, String type, String group, String note) {
    }

    private static final List<PermDef> ALL = new ArrayList<>();
    private static final Map<String, PermDef> BY_CODE = new LinkedHashMap<>();

    static {
        // ---------- 页面菜单 ----------
        menu(MENU_DASHBOARD, "概览", "仪表盘与统计总览");
        menu(MENU_APPLICATIONS, "RA申请审核", "证书申请单的提交、审核、签发、撤销");
        menu(MENU_CERTS, "证书管理", "证书台账、签发、生命周期与导出");
        menu(MENU_CRL, "CRL吊销", "吊销列表查看与下载");
        menu(MENU_OCSP, "OCSP查询", "证书状态在线查询");
        menu(MENU_TSA, "时间戳TSA", "可信时间戳服务");
        menu(MENU_ORGS, "机构管理", "多机构域注册与启停（仅全局系统管理员）");
        menu(MENU_ROOT_CA, "根CA管理", "根CA初始化、密钥封存与门限分持");
        menu(MENU_TEMPLATES, "证书模板", "证书策略模板（KeyUsage/EKU/策略OID）");
        menu(MENU_USERS, "用户管理", "管理账号的建号、改角色、启停与重置口令");
        menu(MENU_ROLES, "角色配置", "角色档案与菜单/按钮权限授权");
        menu(MENU_AUDIT, "审计日志", "操作留痕查询、验签与归档（审计独立）");

        // ---------- RA 申请 ----------
        action("RA申请", APP_SUBMIT, "提交证书申请", "录入员代录申请单，与审核分离");
        action("RA申请", APP_APPROVE, "审核批准", "审核人不得与提交人同一人");
        action("RA申请", APP_REJECT, "审核驳回", "审核意见落库并留痕");
        action("RA申请", APP_ISSUE, "按申请签发", "对已批准申请签发证书");
        action("RA申请", APP_CANCEL, "撤销申请", "仅提交人本人或系统管理员可撤销");

        // ---------- 证书业务 ----------
        action("证书业务", CERT_LIST, "查看证书台账", "证书列表与详情查询");
        action("证书业务", CERT_ISSUE, "签发证书", "在线/CSR/公钥直签三类签发");
        action("证书业务", CERT_ISSUE_SUBCA, "签发二级CA", "可继续签发证书的中间CA，仅系统管理员");
        action("证书业务", CERT_REVOKE, "吊销证书", "吊销后进入 CRL/OCSP");
        action("证书业务", CERT_SUSPEND, "挂起/解除挂起", "certificateHold 与恢复");
        action("证书业务", CERT_RENEW, "证书更新", "原证吊销 + 同公钥重签");
        action("证书业务", CERT_REKEY, "密钥更新", "新密钥对重签，原证吊销");
        action("证书业务", CERT_DOWNLOAD, "下载证书", "公钥证书 PEM");
        action("证书业务", CERT_KEY_DOWNLOAD, "下载私钥", "仅 CA 侧托管私钥的证书，强审计");
        action("证书业务", CERT_EXPORT, "导出PKCS#12", "口令加密导出软件证书");
        action("证书业务", CRL_DOWNLOAD, "下载CRL", "全量/增量吊销列表发布");

        // ---------- 证书模板 ----------
        action("证书模板", TEMPLATE_CREATE, "新建模板", "证书策略管理，从操作员剥离");
        action("证书模板", TEMPLATE_UPDATE, "修改模板", "变更落审计");
        action("证书模板", TEMPLATE_DELETE, "删除模板", "内置模板不可删");

        // ---------- 根CA与密钥 ----------
        action("根CA与密钥", CA_INIT, "初始化根CA", "仅系统管理员/密钥管理员");
        action("根CA与密钥", CA_SEAL, "封存根密钥", "根私钥离线化");
        action("根CA与密钥", CA_UNSEAL, "解封根密钥", "恢复在线态，全程审计");
        action("根CA与密钥", CA_SHARE_VIEW, "查看门限份额状态", "5 份份额存在性与阈值");
        action("根CA与密钥", CA_SHARE_DOWNLOAD, "下载门限份额", "份额建议 5 份分人分管");
        action("根CA与密钥", CA_SHARE_MIGRATE, "门限化迁移", "单点加密私钥切换为 Shamir(3,5)");
        action("根CA与密钥", CA_SHARE_UNSEAL, "份额解封", "任意 3 份重组根私钥（仅内存）");
        action("根CA与密钥", CA_ROOT_DOWNLOAD, "下载根证书", "供子系统信任链配置");
        action("根CA与密钥", CA_SUBCA_DOWNLOAD, "下载二级CA证书", "中间CA链配置");

        // ---------- 机构管理 ----------
        action("机构管理", ORG_CREATE, "新建机构", "登记即生成独立根CA");
        action("机构管理", ORG_SUSPEND, "暂停机构", "停止对外签发");
        action("机构管理", ORG_RESUME, "恢复机构", "恢复签发");
        action("机构管理", ORG_CLOSE, "关闭机构", "CLOSED 终态");

        // ---------- 用户与角色 ----------
        action("用户与角色", USER_LIST, "查看账号", "管理账号列表");
        action("用户与角色", USER_CREATE, "创建账号", "建号必传合规口令并落审计");
        action("用户与角色", USER_UPDATE, "修改账号", "改角色/启停/重置口令");
        action("用户与角色", ROLE_UPDATE, "编辑角色档案", "名称/职责/排序/启停");
        action("用户与角色", ROLE_PERM, "配置角色权限", "菜单与按钮授权，不得越法定边界");

        // ---------- 审计 ----------
        action("审计", AUDIT_VIEW, "查看审计日志", "审计独立，仅审计员可读");
        action("审计", AUDIT_VERIFY, "单条验签", "SM3withSM2 防篡改校验");
        action("审计", AUDIT_CHAIN, "哈希链对账", "检出删改/分叉/空洞");
        action("审计", AUDIT_EXPORT, "审计归档导出", "≥5 年留存材料，报告附签名");
    }

    private PermissionCatalog() {
    }

    private static void menu(String code, String name, String note) {
        add(new PermDef(code, name, TYPE_MENU, "页面菜单", note));
    }

    private static void action(String group, String code, String name, String note) {
        add(new PermDef(code, name, TYPE_ACTION, group, note));
    }

    private static void add(PermDef def) {
        ALL.add(def);
        BY_CODE.put(def.code(), def);
    }

    /** 全部权限点（前端权限树顺序） */
    public static List<PermDef> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** 按编码取权限点定义（未知返回 null） */
    public static PermDef get(String code) {
        return code == null ? null : BY_CODE.get(code.trim());
    }

    /** 是否菜单类权限点 */
    public static boolean isMenu(String code) {
        PermDef def = get(code);
        return def != null && TYPE_MENU.equals(def.type());
    }

    /** 全量权限目录中除审计组与审计菜单外的权限点（系统管理员法定上限：具备除审计外的全部） */
    public static Set<String> allButAudit() {
        Set<String> s = new LinkedHashSet<>();
        for (PermDef d : ALL) {
            if (TYPE_ACTION.equals(d.type()) && d.code().startsWith("audit:")) {
                continue;
            }
            if (MENU_AUDIT.equals(d.code())) {
                continue;
            }
            s.add(d.code());
        }
        return Collections.unmodifiableSet(s);
    }

    /**
     * 角色授权法定上限：预置角色取 {@link RoleCodeEnum} 枚举的 {@link RoleCodeEnum#defaultMax()}
     * （不可越界收窄/扩权）；自定义角色（编码不在枚举内、builtin=0）授权上限为全量权限目录，
     * 由配置方自行组合（责任在配置方）。
     */
    public static Set<String> statutoryMax(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Collections.emptySet();
        }
        RoleCodeEnum rc = RoleCodeEnum.fromCode(roleCode);
        if (rc != null) {
            return rc.defaultMax();
        }
        // 自定义角色：上限 = 全量权限目录（管理员自行组合）
        Set<String> all = new LinkedHashSet<>();
        for (PermDef d : ALL) {
            all.add(d.code());
        }
        return Collections.unmodifiableSet(all);
    }

    /**
     * 权限点是否在该角色法定授权边界内（供角色配置页置灰渲染；越界项不可勾选）。
     * 自定义角色默认全量目录内均可授权。
     */
    public static boolean allowedFor(String roleCode, String permCode) {
        if (permCode == null) {
            return false;
        }
        return statutoryMax(roleCode).contains(permCode.trim());
    }
}
