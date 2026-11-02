package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.ShareAssignDTO;
import com.csssl.dto.ShareUnsealDTO;
import com.csssl.entity.CaCompany;
import com.csssl.service.AuditService;
import com.csssl.service.CaService;
import com.csssl.service.CaSubCaRebuildService;
import com.csssl.service.CompanyService;
import com.csssl.vo.CaShareOverviewVO;
import com.csssl.vo.CaStatusVO;
import com.csssl.vo.RootShareVO;
import com.csssl.vo.RootSharesVO;
import com.csssl.util.CompanyScope;
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
 * 路线A多公司：全部接受 companyUscc 参数（缺省=平台主公司，存量调用零破坏）；
 * 读操作要求公司存在（存量单根标识 default 不依赖公司表记录，保证首启顺序无关），写操作额外要求公司 ACTIVE。
 *
 * <p>数据域强制（effectiveCompany 口径）：公司 ADMIN/公司 KEY_ADMIN 一律锁本公司（显式请求别公司 403）；
 * 全局 ADMIN/KEY_ADMIN 须显式传 companyUscc，缺省只落平台主公司——跨公司密钥域操作必须逐公司显式传参，
 * 不存在"全部公司"写旁路。fail-closed：SUSPENDED/CLOSED 公司只读放行（status/download/shares 查看），
 * init/seal/unseal/migrate/unseal-shares 等密钥形态写操作经 checkCompanyForWrite 拒绝。</p>
 */
@RestController
@RequestMapping("/admin/ca")
@RequiredArgsConstructor
public class CaRootController {

    private final CaService caService;
    private final AuditService auditService;
    private final CompanyService companyService;
    private final CaSubCaRebuildService rebuildService;

    /** 根CA状态（含 SUB_CA 与根密钥封存/门限状态）；公司用户锁本公司 */
    @GetMapping("/status")
    public Result<CaStatusVO> status(@RequestParam(required = false) String companyUscc,
                                     HttpServletRequest request) {
        String company = CompanyScope.effectiveCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForRead(company);
        return Result.success("查询成功", caService.status(company));
    }

    /** 初始化根CA（幂等：已存在则原样返回；私钥丢失则吊销旧根并重建；系统管理员/密钥管理员） */
    @PreAuthorize("hasAuthority('ca:init')")
    @PostMapping("/init")
    public Result<CaStatusVO> init(@RequestParam(required = false) String companyUscc,
                                   HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForWrite(company);
        String username = UserUtils.requireLoginUser().getUsername();
        CaStatusVO vo = caService.initRoot(company);
        auditService.record(username, "CA_INIT", tag(company, null),
                "初始化根CA[" + company + "]：" + vo.getCn() + "（序列号 " + vo.getSerialHex() + "）",
                clientIp(request), company);
        return Result.success("根CA初始化完成", vo);
    }

    /** 封存根私钥（离线化/清除门限解封缓存）：日常签发由 SUB_CA 承担；系统管理员/密钥管理员 */
    @PreAuthorize("hasAuthority('ca:seal')")
    @PostMapping("/seal")
    public Result<CaStatusVO> seal(@RequestParam(required = false) String companyUscc,
                                   HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForWrite(company);
        String username = UserUtils.requireLoginUser().getUsername();
        caService.sealRootKey(company);
        auditService.record(username, "CA_SEAL", tag(company, null),
                "封存根CA私钥（离线化）[" + company + "]", clientIp(request), company);
        CaStatusVO vo = caService.status(company);
        return Result.success("根CA私钥已封存离线，日常签发由 SUB_CA 承担", vo);
    }

    /** 解封根私钥（旧模式恢复在线态；门限分持态请走份额解封接口；系统管理员/密钥管理员） */
    @PreAuthorize("hasAuthority('ca:unseal')")
    @PostMapping("/unseal")
    public Result<CaStatusVO> unseal(@RequestParam(required = false) String companyUscc,
                                     HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForWrite(company);
        String username = UserUtils.requireLoginUser().getUsername();
        caService.unsealRootKey(company);
        auditService.record(username, "CA_UNSEAL", tag(company, null),
                "解封根CA私钥[" + company + "]", clientIp(request), company);
        return Result.success("根CA私钥已解封，恢复在线态", caService.status(company));
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /** 门限份额状态（5 份各自存在性 + 阈值）；公司用户锁本公司 */
    @PreAuthorize("hasAuthority('ca:share:view')")
    @GetMapping("/shares")
    public Result<RootSharesVO> shares(@RequestParam(required = false) String companyUscc,
                                       HttpServletRequest request) {
        String company = CompanyScope.effectiveCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForRead(company);
        return Result.success("查询成功", caService.shareState(company));
    }

    /**
     * 门限化/迁移：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 新根已自动门限化；旧环境（已封存/在线）显式调用本接口迁移。
     */
    @PreAuthorize("hasAuthority('ca:share:migrate')")
    @PostMapping("/shares/migrate")
    public Result<RootSharesVO> migrateShares(@RequestParam(required = false) String companyUscc,
                                              HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForWrite(company);
        String username = UserUtils.requireLoginUser().getUsername();
        RootSharesVO state = caService.migrateRootShares(company);
        auditService.record(username, "CA_SHARE_GENERATE", tag(company, "ROOT"),
                "根私钥切换为 Shamir(3,5) 门限分持[" + company + "]（5 份份额落盘，原始加密文件已删除）",
                clientIp(request), company);
        return Result.success("根私钥已切换为 Shamir(3,5) 门限分持，请下载并离线保管 5 份份额", state);
    }

    /** 下载单份门限份额（JSON 文本，供密钥管理员离线保管；建议 5 份分人分管） */
    @PreAuthorize("hasAuthority('ca:share:download')")
    @GetMapping("/shares/{index}")
    public Result<RootShareVO> downloadShare(@PathVariable int index,
                                             @RequestParam(required = false) String companyUscc,
                                             HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForRead(company);
        if (index < 1 || index > CaService.SHARE_TOTAL) {
            throw new IllegalArgumentException("份额编号无效（有效范围 1~" + CaService.SHARE_TOTAL + "）");
        }
        String json = caService.readShareJson(company, index);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CA_SHARE_DOWNLOAD", tag(company, "SHARE-" + index),
                "导出公司[" + company + "]第 " + index + " 份根密钥门限份额（请离线保管）", clientIp(request), company);
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
    public Result<CaStatusVO> unsealShares(@RequestParam(required = false) String companyUscc,
                                           @Valid @RequestBody ShareUnsealDTO body,
                                           HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForWrite(company);
        List<String> shares = body.getShares();
        String username = UserUtils.requireLoginUser().getUsername();
        caService.unsealRootKeyWithShares(company, shares);
        auditService.record(username, "CA_UNSEAL_SHARES", tag(company, "ROOT"),
                "通过 " + (shares == null ? 0 : shares.size()) + " 份门限份额解封根私钥（内存缓存）[" + company + "]",
                clientIp(request), company);
        return Result.success("根私钥已通过 " + (shares == null ? 0 : shares.size()) + " 份份额解封（内存缓存），可进行 SUB_CA 轮换等维护", caService.status(company));
    }

    // ==================== 门限份额保管人绑定（建根/开公司时指定，一人一份） ====================

    /**
     * 指定/重指定根私钥份额保管人：把已生成的 5 份份额各绑定一名保管人，落表（一人一份）。
     * 用于开公司时自动指派，或存量/主公司后续手动指派。
     */
    @PreAuthorize("hasAuthority('ca:share:assign')")
    @PostMapping("/shares/assign")
    public Result<Void> assignShares(@Valid @RequestBody ShareAssignDTO body, HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), body.getCompanyUscc());
        checkCompanyForWrite(company);
        String username = UserUtils.requireLoginUser().getUsername();
        caService.assignRootShares(company, body.getHolderUsernames());
        auditService.record(username, "CA_SHARE_ASSIGN", tag(company, null),
                "指定根私钥份额保管人[" + company + "]：" + String.join(",", body.getHolderUsernames()), clientIp(request), company);
        return Result.success("已指定 " + (body.getHolderUsernames() == null ? 0 : body.getHolderUsernames().size()) + " 名密钥保管人（一人一份）", null);
    }

    /**
     * 某公司密钥份额总览（一次调用返回全部，前端无需多次往返）：
     * <ul>
     *   <li>{@code holders}：5 名保管人名单（份额编号/姓名/账号，不含份额明文）——对密钥管理员/系统管理员/公司管理员可见；</li>
     *   <li>{@code mine}：当前登录人本人持有的份额明文——仅保管人本人可见，非保管人返回 null（不报错、不泄露他人份额）。</li>
     * </ul>
     */
    @PreAuthorize("hasAnyAuthority('ca:share:view','ca:share:assign','ca:subca:rebuild:apply','ca:subca:rebuild:execute')")
    @GetMapping("/shares/overview")
    public Result<CaShareOverviewVO> shareOverview(@RequestParam(required = false) String companyUscc) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForRead(company);
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("查询成功", rebuildService.overview(company, username));
    }

    /** 导出根证书 PEM（供各子系统信任链配置） */
    @PreAuthorize("hasAuthority('ca:root:download')")
    @GetMapping("/root/download")
    public String downloadRoot(@RequestParam(required = false) String companyUscc,
                               HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForRead(company);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", tag(company, "ROOT"),
                "导出根CA证书[" + company + "]", clientIp(request), company);
        return caService.exportRootPem(company);
    }

    /** 导出当前 SUB_CA 证书 PEM（中间CA链配置） */
    @PreAuthorize("hasAuthority('ca:subca:download')")
    @GetMapping("/subca/download")
    public String downloadSubCa(@RequestParam(required = false) String companyUscc,
                                HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkCompanyForRead(company);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", tag(company, "SUB_CA"),
                "导出服务签发二级CA证书[" + company + "]", clientIp(request), company);
        return caService.exportSubCaPem(company);
    }

    /** 读操作：非 default 公司必须已登记（default 不查表，保证启动首序与存量行为不变） */
    private void checkCompanyForRead(String companyUscc) {
        if (!CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            companyService.get(companyUscc);
        }
    }

    /** 写操作：非 default 公司还须 ACTIVE（SUSPENDED/CLOSED 拒绝密钥形态变更与重建） */
    private void checkCompanyForWrite(String companyUscc) {
        if (!CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            companyService.requireActive(companyUscc);
        }
    }

    /** 审计 target：默认域沿用原 target 文案（兼容存量检索），新公司加 companyUscc 前缀 */
    private String tag(String companyUscc, String legacyTarget) {
        String base = legacyTarget == null ? "" : legacyTarget;
        return CaCompany.LEGACY_NAMESPACE.equals(companyUscc) ? base : companyUscc + ":" + base;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
