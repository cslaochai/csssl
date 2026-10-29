package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CaStatusVo;
import com.csssl.entity.CaOrg;
import com.csssl.service.AuditService;
import com.csssl.service.CaService;
import com.csssl.service.OrgService;
import com.csssl.service.PermissionService;
import com.csssl.util.PermissionCatalog;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 根CA管理接口（需登录）：状态查询、初始化、根密钥封存/解封、Shamir(3,5) 门限分持、证书导出。
 * 路线A多机构：全部接受 orgKey 参数（缺省=平台主机构，存量调用零破坏）；
 * 读操作要求机构存在（存量单根标识 default 不依赖机构表记录，保证首启顺序无关），写操作额外要求机构 ACTIVE。
 *
 * <p>数据域强制（effectiveOrg 口径）：机构 ADMIN/机构 KEY_ADMIN 一律锁本机构（显式请求别机构 403）；
 * 全局 ADMIN/KEY_ADMIN 须显式传 orgKey，缺省只落平台主机构——跨机构密钥域操作必须逐机构显式传参，
 * 不存在"全部机构"写旁路。fail-closed：SUSPENDED/CLOSED 机构只读放行（status/download/shares 查看），
 * init/seal/unseal/migrate/unseal-shares 等密钥形态写操作经 checkOrgForWrite 拒绝。</p>
 */
@RestController
@RequestMapping("/admin/ca")
@RequiredArgsConstructor
public class CaRootController {

    private final CaService caService;
    private final AuditService auditService;
    private final OrgService orgService;
    private final PermissionService permissionService;

    /** 根CA状态（含 SUB_CA 与根密钥封存/门限状态）；机构用户锁本机构 */
    @GetMapping("/status")
    public Result<CaStatusVo> status(@RequestParam(required = false) String orgKey,
                                     HttpServletRequest request) {
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForRead(org);
        return Result.success("查询成功", caService.status(org));
    }

    /** 初始化根CA（幂等：已存在则原样返回；私钥丢失则吊销旧根并重建；系统管理员/密钥管理员） */
    @PostMapping("/init")
    public Result<CaStatusVo> init(@RequestParam(required = false) String orgKey,
                                   HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_INIT);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForWrite(org);
        String username = RoleCode.currentUser(request);
        CaStatusVo vo = caService.initRoot(org);
        auditService.record(username, "CA_INIT", tag(org, null),
                "初始化根CA[" + org + "]：" + vo.getCn() + "（序列号 " + vo.getSerialHex() + "）",
                clientIp(request), org);
        return Result.success("根CA初始化完成", vo);
    }

    /** 封存根私钥（离线化/清除门限解封缓存）：日常签发由 SUB_CA 承担；系统管理员/密钥管理员 */
    @PostMapping("/seal")
    public Result<CaStatusVo> seal(@RequestParam(required = false) String orgKey,
                                   HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_SEAL);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForWrite(org);
        String username = RoleCode.currentUser(request);
        caService.sealRootKey(org);
        auditService.record(username, "CA_SEAL", tag(org, null),
                "封存根CA私钥（离线化）[" + org + "]", clientIp(request), org);
        CaStatusVo vo = caService.status(org);
        return Result.success("根CA私钥已封存离线，日常签发由 SUB_CA 承担", vo);
    }

    /** 解封根私钥（旧模式恢复在线态；门限分持态请走份额解封接口；系统管理员/密钥管理员） */
    @PostMapping("/unseal")
    public Result<CaStatusVo> unseal(@RequestParam(required = false) String orgKey,
                                     HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_UNSEAL);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForWrite(org);
        String username = RoleCode.currentUser(request);
        caService.unsealRootKey(org);
        auditService.record(username, "CA_UNSEAL", tag(org, null),
                "解封根CA私钥[" + org + "]", clientIp(request), org);
        return Result.success("根CA私钥已解封，恢复在线态", caService.status(org));
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /** 门限份额状态（5 份各自存在性 + 阈值）；机构用户锁本机构 */
    @GetMapping("/shares")
    public Result<Map<String, Object>> shares(@RequestParam(required = false) String orgKey,
                                              HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_SHARE_VIEW);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForRead(org);
        return Result.success("查询成功", caService.shareState(org));
    }

    /**
     * 门限化/迁移：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 新根已自动门限化；旧环境（已封存/在线）显式调用本接口迁移。
     */
    @PostMapping("/shares/migrate")
    public Result<Map<String, Object>> migrateShares(@RequestParam(required = false) String orgKey,
                                                     HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_SHARE_MIGRATE);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForWrite(org);
        String username = RoleCode.currentUser(request);
        Map<String, Object> state = caService.migrateRootShares(org);
        auditService.record(username, "CA_SHARE_GENERATE", tag(org, "ROOT"),
                "根私钥切换为 Shamir(3,5) 门限分持[" + org + "]（5 份份额落盘，原始加密文件已删除）",
                clientIp(request), org);
        return Result.success("根私钥已切换为 Shamir(3,5) 门限分持，请下载并离线保管 5 份份额", state);
    }

    /** 下载单份门限份额（JSON 文本，供密钥管理员离线保管；建议 5 份分人分管） */
    @GetMapping("/shares/{index}")
    public Result<Map<String, Object>> downloadShare(@PathVariable int index,
                                                     @RequestParam(required = false) String orgKey,
                                                     HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_SHARE_DOWNLOAD);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForRead(org);
        if (index < 1 || index > CaService.SHARE_TOTAL) {
            throw new IllegalArgumentException("份额编号无效（有效范围 1~" + CaService.SHARE_TOTAL + "）");
        }
        String json = caService.readShareJson(org, index);
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CA_SHARE_DOWNLOAD", tag(org, "SHARE-" + index),
                "导出机构[" + org + "]第 " + index + " 份根密钥门限份额（请离线保管）", clientIp(request), org);
        @SuppressWarnings("unchecked")
        Map<String, Object> share;
        try {
            share = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("份额文件解析失败: " + e.getMessage(), e);
        }
        return Result.success("第 " + index + " 份份额", share);
    }

    /** 份额解封：提交任意 3 份门限份额重组根私钥（仅内存，用后请封存清除） */
    @PostMapping("/unseal-shares")
    public Result<CaStatusVo> unsealShares(@RequestParam(required = false) String orgKey,
                                           @RequestBody Map<String, List<String>> body,
                                           HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.CA_SHARE_UNSEAL);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForWrite(org);
        List<String> shares = body == null ? null : body.get("shares");
        String username = RoleCode.currentUser(request);
        caService.unsealRootKeyWithShares(org, shares);
        auditService.record(username, "CA_UNSEAL_SHARES", tag(org, "ROOT"),
                "通过 " + (shares == null ? 0 : shares.size()) + " 份门限份额解封根私钥（内存缓存）[" + org + "]",
                clientIp(request), org);
        return Result.success("根私钥已通过 " + (shares == null ? 0 : shares.size()) + " 份份额解封（内存缓存），可进行 SUB_CA 轮换等维护", caService.status(org));
    }

    /** 导出根证书 PEM（供各子系统信任链配置） */
    @GetMapping("/root/download")
    public String downloadRoot(@RequestParam(required = false) String orgKey,
                               HttpServletRequest request) {
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForRead(org);
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CERT_DOWNLOAD", tag(org, "ROOT"),
                "导出根CA证书[" + org + "]", clientIp(request), org);
        return caService.exportRootPem(org);
    }

    /** 导出当前 SUB_CA 证书 PEM（中间CA链配置） */
    @GetMapping("/subca/download")
    public String downloadSubCa(@RequestParam(required = false) String orgKey,
                                HttpServletRequest request) {
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkOrgForRead(org);
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CERT_DOWNLOAD", tag(org, "SUB_CA"),
                "导出服务签发二级CA证书[" + org + "]", clientIp(request), org);
        return caService.exportSubCaPem(org);
    }

    /** 读操作：非 default 机构必须已登记（default 不查表，保证启动首序与存量行为不变） */
    private void checkOrgForRead(String orgKey) {
        if (!CaOrg.LEGACY_ORG_KEY.equals(orgKey)) {
            orgService.get(orgKey);
        }
    }

    /** 写操作：非 default 机构还须 ACTIVE（SUSPENDED/CLOSED 拒绝密钥形态变更与重建） */
    private void checkOrgForWrite(String orgKey) {
        if (!CaOrg.LEGACY_ORG_KEY.equals(orgKey)) {
            orgService.requireActive(orgKey);
        }
    }

    /** 审计 target：默认域沿用原 target 文案（兼容存量检索），新机构加 orgKey 前缀 */
    private String tag(String orgKey, String legacyTarget) {
        String base = legacyTarget == null ? "" : legacyTarget;
        return CaOrg.LEGACY_ORG_KEY.equals(orgKey) ? base : orgKey + ":" + base;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
