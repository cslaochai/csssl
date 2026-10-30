package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.vo.CaStatusVO;
import com.csssl.vo.RootShareVO;
import com.csssl.vo.RootSharesVO;
import com.csssl.dto.ShareUnsealDTO;
import com.csssl.entity.CaTenant;
import com.csssl.service.AuditService;
import com.csssl.service.CaService;
import com.csssl.service.TenantService;
import com.csssl.util.TenantScope;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 根CA管理接口（需登录）：状态查询、初始化、根密钥封存/解封、Shamir(3,5) 门限分持、证书导出。
 * 路线A多租户：全部接受 tenantKey 参数（缺省=平台主租户，存量调用零破坏）；
 * 读操作要求租户存在（存量单根标识 default 不依赖租户表记录，保证首启顺序无关），写操作额外要求租户 ACTIVE。
 *
 * <p>数据域强制（effectiveTenant 口径）：租户 ADMIN/租户 KEY_ADMIN 一律锁本租户（显式请求别租户 403）；
 * 全局 ADMIN/KEY_ADMIN 须显式传 tenantKey，缺省只落平台主租户——跨租户密钥域操作必须逐租户显式传参，
 * 不存在"全部租户"写旁路。fail-closed：SUSPENDED/CLOSED 租户只读放行（status/download/shares 查看），
 * init/seal/unseal/migrate/unseal-shares 等密钥形态写操作经 checkTenantForWrite 拒绝。</p>
 */
@RestController
@RequestMapping("/admin/ca")
@RequiredArgsConstructor
public class CaRootController {

    private final CaService caService;
    private final AuditService auditService;
    private final TenantService tenantService;

    /** 根CA状态（含 SUB_CA 与根密钥封存/门限状态）；租户用户锁本租户 */
    @GetMapping("/status")
    public Result<CaStatusVO> status(@RequestParam(required = false) String tenantKey,
                                     HttpServletRequest request) {
        String tenant = TenantScope.effectiveTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForRead(tenant);
        return Result.success("查询成功", caService.status(tenant));
    }

    /** 初始化根CA（幂等：已存在则原样返回；私钥丢失则吊销旧根并重建；系统管理员/密钥管理员） */
    @PreAuthorize("hasAuthority('ca:init')")
    @PostMapping("/init")
    public Result<CaStatusVO> init(@RequestParam(required = false) String tenantKey,
                                   HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForWrite(tenant);
        String username = UserUtils.requireLoginUser().getUsername();
        CaStatusVO vo = caService.initRoot(tenant);
        auditService.record(username, "CA_INIT", tag(tenant, null),
                "初始化根CA[" + tenant + "]：" + vo.getCn() + "（序列号 " + vo.getSerialHex() + "）",
                clientIp(request), tenant);
        return Result.success("根CA初始化完成", vo);
    }

    /** 封存根私钥（离线化/清除门限解封缓存）：日常签发由 SUB_CA 承担；系统管理员/密钥管理员 */
    @PreAuthorize("hasAuthority('ca:seal')")
    @PostMapping("/seal")
    public Result<CaStatusVO> seal(@RequestParam(required = false) String tenantKey,
                                   HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForWrite(tenant);
        String username = UserUtils.requireLoginUser().getUsername();
        caService.sealRootKey(tenant);
        auditService.record(username, "CA_SEAL", tag(tenant, null),
                "封存根CA私钥（离线化）[" + tenant + "]", clientIp(request), tenant);
        CaStatusVO vo = caService.status(tenant);
        return Result.success("根CA私钥已封存离线，日常签发由 SUB_CA 承担", vo);
    }

    /** 解封根私钥（旧模式恢复在线态；门限分持态请走份额解封接口；系统管理员/密钥管理员） */
    @PreAuthorize("hasAuthority('ca:unseal')")
    @PostMapping("/unseal")
    public Result<CaStatusVO> unseal(@RequestParam(required = false) String tenantKey,
                                     HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForWrite(tenant);
        String username = UserUtils.requireLoginUser().getUsername();
        caService.unsealRootKey(tenant);
        auditService.record(username, "CA_UNSEAL", tag(tenant, null),
                "解封根CA私钥[" + tenant + "]", clientIp(request), tenant);
        return Result.success("根CA私钥已解封，恢复在线态", caService.status(tenant));
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /** 门限份额状态（5 份各自存在性 + 阈值）；租户用户锁本租户 */
    @PreAuthorize("hasAuthority('ca:share:view')")
    @GetMapping("/shares")
    public Result<RootSharesVO> shares(@RequestParam(required = false) String tenantKey,
                                       HttpServletRequest request) {
        String tenant = TenantScope.effectiveTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForRead(tenant);
        return Result.success("查询成功", caService.shareState(tenant));
    }

    /**
     * 门限化/迁移：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 新根已自动门限化；旧环境（已封存/在线）显式调用本接口迁移。
     */
    @PreAuthorize("hasAuthority('ca:share:migrate')")
    @PostMapping("/shares/migrate")
    public Result<RootSharesVO> migrateShares(@RequestParam(required = false) String tenantKey,
                                              HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForWrite(tenant);
        String username = UserUtils.requireLoginUser().getUsername();
        RootSharesVO state = caService.migrateRootShares(tenant);
        auditService.record(username, "CA_SHARE_GENERATE", tag(tenant, "ROOT"),
                "根私钥切换为 Shamir(3,5) 门限分持[" + tenant + "]（5 份份额落盘，原始加密文件已删除）",
                clientIp(request), tenant);
        return Result.success("根私钥已切换为 Shamir(3,5) 门限分持，请下载并离线保管 5 份份额", state);
    }

    /** 下载单份门限份额（JSON 文本，供密钥管理员离线保管；建议 5 份分人分管） */
    @PreAuthorize("hasAuthority('ca:share:download')")
    @GetMapping("/shares/{index}")
    public Result<RootShareVO> downloadShare(@PathVariable int index,
                                             @RequestParam(required = false) String tenantKey,
                                             HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForRead(tenant);
        if (index < 1 || index > CaService.SHARE_TOTAL) {
            throw new IllegalArgumentException("份额编号无效（有效范围 1~" + CaService.SHARE_TOTAL + "）");
        }
        String json = caService.readShareJson(tenant, index);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CA_SHARE_DOWNLOAD", tag(tenant, "SHARE-" + index),
                "导出租户[" + tenant + "]第 " + index + " 份根密钥门限份额（请离线保管）", clientIp(request), tenant);
        try {
            RootShareVO share = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, RootShareVO.class);
            return Result.success("第 " + index + " 份份额", share);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("份额文件解析失败: " + e.getMessage(), e);
        }
    }

    /** 份额解封：提交任意 3 份门限份额重组根私钥（仅内存，用后请封存清除） */
    @PreAuthorize("hasAuthority('ca:share:unseal')")
    @PostMapping("/unseal-shares")
    public Result<CaStatusVO> unsealShares(@RequestParam(required = false) String tenantKey,
                                           @Valid @RequestBody ShareUnsealDTO body,
                                           HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForWrite(tenant);
        List<String> shares = body.getShares();
        String username = UserUtils.requireLoginUser().getUsername();
        caService.unsealRootKeyWithShares(tenant, shares);
        auditService.record(username, "CA_UNSEAL_SHARES", tag(tenant, "ROOT"),
                "通过 " + (shares == null ? 0 : shares.size()) + " 份门限份额解封根私钥（内存缓存）[" + tenant + "]",
                clientIp(request), tenant);
        return Result.success("根私钥已通过 " + (shares == null ? 0 : shares.size()) + " 份份额解封（内存缓存），可进行 SUB_CA 轮换等维护", caService.status(tenant));
    }

    /** 导出根证书 PEM（供各子系统信任链配置） */
    @GetMapping("/root/download")
    public String downloadRoot(@RequestParam(required = false) String tenantKey,
                               HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForRead(tenant);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", tag(tenant, "ROOT"),
                "导出根CA证书[" + tenant + "]", clientIp(request), tenant);
        return caService.exportRootPem(tenant);
    }

    /** 导出当前 SUB_CA 证书 PEM（中间CA链配置） */
    @GetMapping("/subca/download")
    public String downloadSubCa(@RequestParam(required = false) String tenantKey,
                                HttpServletRequest request) {
        String tenant = TenantScope.requireExplicitTenant(UserUtils.requireLoginUser().getTenantKey(), tenantKey);
        checkTenantForRead(tenant);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", tag(tenant, "SUB_CA"),
                "导出服务签发二级CA证书[" + tenant + "]", clientIp(request), tenant);
        return caService.exportSubCaPem(tenant);
    }

    /** 读操作：非 default 租户必须已登记（default 不查表，保证启动首序与存量行为不变） */
    private void checkTenantForRead(String tenantKey) {
        if (!CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)) {
            tenantService.get(tenantKey);
        }
    }

    /** 写操作：非 default 租户还须 ACTIVE（SUSPENDED/CLOSED 拒绝密钥形态变更与重建） */
    private void checkTenantForWrite(String tenantKey) {
        if (!CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)) {
            tenantService.requireActive(tenantKey);
        }
    }

    /** 审计 target：默认域沿用原 target 文案（兼容存量检索），新租户加 tenantKey 前缀 */
    private String tag(String tenantKey, String legacyTarget) {
        String base = legacyTarget == null ? "" : legacyTarget;
        return CaTenant.LEGACY_TENANT_KEY.equals(tenantKey) ? base : tenantKey + ":" + base;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
