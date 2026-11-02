package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.*;
import com.csssl.entity.CaCert;
import com.csssl.entity.CertTemplate;
import com.csssl.service.AuditService;
import com.csssl.service.CertService;
import com.csssl.service.CertTemplateService;
import com.csssl.service.PermissionService;
import com.csssl.util.CompanyScope;
import com.csssl.util.UserUtils;
import com.csssl.util.PermissionCatalog;
import com.csssl.vo.CertOverviewVO;
import com.csssl.vo.CertVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 证书管理接口（需登录）：列表/详情/签发/CSR签发/更新/下载/概览。
 *
 * <p>路线A数据域（服务端强制，effectiveCompany 口径）：
 * <ul>
 *   <li>列表：公司用户强制本公司过滤；全局用户可按 ?companyUscc= 过滤，不传=跨公司全量只读（唯一读口，无写旁路）。</li>
 *   <li>签发三口（issue/csr/public-key）：受理域=effectiveCompany（公司用户锁本公司、越域 403；
 *       全局用户显式 ?companyUscc=，缺省 default），并调用 CertService 带 companyUscc 首参正式版。</li>
 *   <li>行级读（detail/cert.pem/key.pem/p12）：公司用户仅本公司证书行（越域 403）；全局用户跨公司只读放行。</li>
 *   <li>行级写：更新/重签不走直接端点（防单人执行，管理员也绕不过）：统一提交证书操作单
 *       （CertOpTicketController /admin/cert-ops），由持 cert:op:approve 的他人审批后执行。
 * </ul></p>
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class CertController {

    private final CertService certService;
    private final AuditService auditService;
    private final CertTemplateService templateService;
    private final PermissionService permissionService;

    /**
     * 证书列表（分页 + 类型/状态/关键字/公司筛选；需 操作员/管理员/密钥管理员）
     */
    @PreAuthorize("hasAuthority('cert:list')")
    @GetMapping("/certs")
    public Result<Page<CertVO>> page(@Valid CertPageQueryDTODTO query, HttpServletRequest request) {
        // 公司用户锁本公司（显式传别公司 403）；全局用户不传=null=跨公司全量只读
        String scope = CompanyScope.readCompanyScope(UserUtils.requireLoginUser().getCompanyUscc(), query.getCompanyUscc());
        return Result.success("查询成功", certService.page(query.getPageNo(), query.getPageSize(),
                query.getCertType(), query.getStatus(), query.getKeyword(), scope));
    }

    /**
     * 证书详情（公司用户仅本公司证书行，越域 403；全局用户跨公司只读放行）
     */
    @GetMapping("/certs/{id}")
    public Result<CertVO> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("查询成功", certService.detailVisible(id, UserUtils.requireLoginUser().getCompanyUscc()));
    }

    /**
     * 在线签发（内部生成密钥对，私钥落盘；需 CA操作员/系统管理员，SUB_CA 仅系统管理员）
     */
    @PreAuthorize("hasAuthority('cert:issue')")
    @PostMapping("/certs")
    public Result<CertVO> issue(@RequestBody IssueCertRequestDTO req,
                                @RequestParam(required = false) String companyUscc,
                                HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkSubCaAndTemplate(req.getTemplateId(), req.getCertType(), company, request);
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("签发成功", certService.issue(company, req, username, clientIp(request)));
    }

    /**
     * CSR 签发（主体与公钥取自 CSR；需 CA操作员/系统管理员，SUB_CA 仅系统管理员）
     */
    @PreAuthorize("hasAuthority('cert:issue')")
    @PostMapping("/certs/csr")
    public Result<CertVO> issueFromCsr(@RequestBody CsrIssueRequestDTO req,
                                       @RequestParam(required = false) String companyUscc,
                                       HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkSubCaAndTemplate(req.getTemplateId(), req.getCertType(), company, request);
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("CSR签发成功", certService.issueFromCsr(company,
                req.getCsrPem(), req.getCertType(), req.getValidityDays(),
                req.getSan(), req.getDescription(), username, clientIp(request), req.getTemplateId()));
    }

    /**
     * 公钥签发（RA 场景）：申请方自持私钥，仅提交 Subject DN + 公钥 PEM（需 CA操作员/系统管理员，SUB_CA 仅系统管理员）
     */
    @PreAuthorize("hasAuthority('cert:issue')")
    @PostMapping("/certs/public-key")
    public Result<CertVO> issueWithPublicKey(@RequestBody PublicKeyIssueDTO req,
                                             @RequestParam(required = false) String companyUscc,
                                             HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc);
        checkSubCaAndTemplate(req.getTemplateId(), req.getCertType(), company, request);
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("公钥签发成功", certService.issueWithPublicKey(company, req, null, username, clientIp(request)));
    }

    /**
     * 下载证书 PEM（公钥证书；需 操作员/管理员，落审计；行级读按数据域收敛）
     */
    @PreAuthorize("hasAuthority('cert:download')")
    @GetMapping("/certs/{id}/cert.pem")
    public String downloadCert(@PathVariable Long id, HttpServletRequest request) {
        CaCert row = certService.findById(id);
        CompanyScope.requireRowVisible(UserUtils.requireLoginUser().getCompanyUscc(), row.getCompanyUscc());
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "下载证书：" + row.getSubjectCn(), clientIp(request), CompanyScope.companyOfRow(row.getCompanyUscc()));
        return certService.certPem(id);
    }

    /**
     * 导出证书压缩包（ZIP，不加密）：内含证书链 PEM，供 HIS 等外部系统「上传 zip 回填证书」导入。
     *
     * <p>与 cert.pem 同一数据域与审计口径；不设口令（口令需线下传递反而抬高导入门槛），
     * 私钥不出 CA，包内仅公钥证书链。
     */
    @PreAuthorize("hasAuthority('cert:download')")
    @GetMapping("/certs/{id}/export/zip")
    public ResponseEntity<byte[]> exportCertZip(@PathVariable Long id, HttpServletRequest request) {
        CaCert row = certService.findById(id);
        CompanyScope.requireRowVisible(UserUtils.requireLoginUser().getCompanyUscc(), row.getCompanyUscc());
        byte[] zip = certService.certZipBytes(id);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "导出证书压缩包(ZIP)：" + row.getSubjectCn(), clientIp(request),
                CompanyScope.companyOfRow(row.getCompanyUscc()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cert-" + row.getSerialHex() + ".zip\"")
                .body(zip);
    }

    /**
     * 下载私钥 PEM（需 管理员/密钥管理员；受 allow-private-key-download 开关控制，落审计；行级读按数据域收敛）
     */
    @PreAuthorize("hasAuthority('cert:key:download')")
    @GetMapping("/certs/{id}/key.pem")
    public String downloadKey(@PathVariable Long id, HttpServletRequest request) {
        CaCert row = certService.findById(id);
        CompanyScope.requireRowVisible(UserUtils.requireLoginUser().getCompanyUscc(), row.getCompanyUscc());
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "下载私钥：" + row.getSubjectCn(), clientIp(request), CompanyScope.companyOfRow(row.getCompanyUscc()));
        return certService.keyPem(id);
    }

    /**
     * 导出 PKCS#12（软件证书场景，PBE 口令加密；需 管理员/密钥管理员，落审计）。
     * 口令走 POST body 而非 query 参数，避免进入访问日志/Referer 留痕。
     * 数据域：公司用户仅本公司证书；全局用户跨公司导出放行（密钥介质导出属读取行为，行级归属留审计）。
     */
    @PreAuthorize("hasAuthority('cert:export')")
    @PostMapping("/certs/{id}/export/p12")
    public ResponseEntity<byte[]> exportP12(@PathVariable Long id,
                                            @Valid @RequestBody(required = false) CertExportP12DTO body,
                                            HttpServletRequest request) {
        CaCert row = certService.findById(id);
        CompanyScope.requireRowVisible(UserUtils.requireLoginUser().getCompanyUscc(), row.getCompanyUscc());
        String password = body == null ? null : body.getPassword();
        byte[] p12 = certService.exportP12(id, password);
        String username = UserUtils.requireLoginUser().getUsername();
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "导出PKCS#12（口令加密）：" + row.getSubjectCn(), clientIp(request),
                CompanyScope.companyOfRow(row.getCompanyUscc()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-pkcs12"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cert-" + id + ".p12\"")
                .body(p12);
    }

    /**
     * 概览统计（仪表盘；按当前公司域过滤，公司用户锁本公司）
     */
    @GetMapping("/overview")
    public Result<CertOverviewVO> overview(@RequestParam(required = false) String companyUscc,
                                           HttpServletRequest request) {
        return Result.success("查询成功", certService.overview(CompanyScope.effectiveCompany(UserUtils.requireLoginUser().getCompanyUscc(), companyUscc)));
    }

    /**
     * RA 直发接口收紧：目标模板（或直传类型）为 SUB_CA 时仅 ADMIN 可发（OPERATOR 不够，
     * 防止操作员越权签发可继续签发证书的中间CA）；带模板ID时先校验模板存在、未被删除
     * 且对签发域可见（全局模板或本公司模板，模板可见集口径）。
     */
    private void checkSubCaAndTemplate(Long templateId, String reqCertType, String company, HttpServletRequest request) {
        if (templateId != null) {
            CertTemplate tpl = templateService.detailVisible(templateId, company);
            if ("SUB_CA".equalsIgnoreCase(tpl.getCertType())) {
                permissionService.require(request, PermissionCatalog.CERT_ISSUE_SUBCA);
            }
            return;
        }
        if ("SUB_CA".equalsIgnoreCase(reqCertType)) {
            permissionService.require(request, PermissionCatalog.CERT_ISSUE_SUBCA);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
