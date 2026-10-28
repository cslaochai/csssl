package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CertVo;
import com.csssl.dto.CsrIssueRequest;
import com.csssl.dto.IssueCertRequest;
import com.csssl.entity.CaCert;
import com.csssl.entity.CertTemplate;
import com.csssl.mapper.CaCertMapper;
import com.csssl.service.AuditService;
import com.csssl.service.CertService;
import com.csssl.service.CertTemplateService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 证书管理接口（需登录）：列表/详情/签发/CSR签发/吊销/下载/概览。
 *
 * <p>路线A数据域（服务端强制，effectiveOrg 口径）：
 * <ul>
 *   <li>列表：机构用户强制本机构过滤；全局用户可按 ?orgKey= 过滤，不传=跨机构全量只读（唯一读口，无写旁路）。</li>
 *   <li>签发三口（issue/csr/public-key）：受理域=effectiveOrg（机构用户锁本机构、越域 403；
 *       全局用户显式 ?orgKey=，缺省 default），并调用 CertService 带 orgKey 首参正式版。</li>
 *   <li>行级读（detail/cert.pem/key.pem/p12）：机构用户仅本机构证书行（越域 403）；全局用户跨机构只读放行。</li>
 *   <li>行级写（revoke/suspend/unsuspend/renew/rekey）：操作域（effectiveOrg）必须等于证书行归属机构，
 *       否则 403——全局用户写非 default 域证书必须显式传该机构 orgKey。</li>
 * </ul></p>
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class CertController {

    private final CertService certService;
    private final CaCertMapper certMapper;
    private final AuditService auditService;
    private final CertTemplateService templateService;

    /** 证书列表（分页 + 类型/状态/关键字/机构筛选；需 操作员/管理员/密钥管理员） */
    @GetMapping("/certs")
    public Result<Page<CertVo>> page(@RequestParam(defaultValue = "1") long pageNo,
                                     @RequestParam(defaultValue = "10") long pageSize,
                                     @RequestParam(required = false) String certType,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String orgKey,
                                     HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        // 机构用户锁本机构（显式传别机构 403）；全局用户不传=null=跨机构全量只读
        String scope = RoleCode.readOrgScope(request, orgKey);
        LambdaQueryWrapper<CaCert> qw = new LambdaQueryWrapper<>();
        if (scope != null) {
            qw.eq(CaCert::getOrgKey, scope);
        }
        if (certType != null && !certType.isBlank()) {
            qw.eq(CaCert::getCertType, certType);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(CaCert::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CaCert::getSubjectCn, keyword).or().like(CaCert::getSerialHex, keyword));
        }
        qw.orderByDesc(CaCert::getCreatedAt);
        Page<CaCert> page = certMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return Result.success("查询成功", (Page<CertVo>) page.convert(CertVo::from));
    }

    /** 证书详情（机构用户仅本机构证书行，越域 403；全局用户跨机构只读放行） */
    @GetMapping("/certs/{id}")
    public Result<CertVo> detail(@PathVariable Long id, HttpServletRequest request) {
        CaCert row = certService.findById(id);
        RoleCode.requireRowVisible(request, row.getOrgKey());
        return Result.success("查询成功", CertVo.from(row));
    }

    /** 在线签发（内部生成密钥对，私钥落盘；需 CA操作员/系统管理员，SUB_CA 仅系统管理员） */
    @PostMapping("/certs")
    public Result<CertVo> issue(@RequestBody IssueCertRequest req,
                                @RequestParam(required = false) String orgKey,
                                HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkSubCaAndTemplate(req.getTemplateId(), req.getCertType(), org, request);
        String username = RoleCode.currentUser(request);
        return Result.success("签发成功", certService.issue(org, req, username, clientIp(request)));
    }

    /** CSR 签发（主体与公钥取自 CSR；需 CA操作员/系统管理员，SUB_CA 仅系统管理员） */
    @PostMapping("/certs/csr")
    public Result<CertVo> issueFromCsr(@RequestBody CsrIssueRequest req,
                                       @RequestParam(required = false) String orgKey,
                                       HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkSubCaAndTemplate(req.getTemplateId(), req.getCertType(), org, request);
        String username = RoleCode.currentUser(request);
        return Result.success("CSR签发成功", certService.issueFromCsr(org,
                req.getCsrPem(), req.getCertType(), req.getValidityDays(),
                req.getSan(), req.getDescription(), username, clientIp(request), req.getTemplateId()));
    }

    /** 公钥签发（RA 场景）：申请方自持私钥，仅提交 Subject DN + 公钥 PEM（需 CA操作员/系统管理员，SUB_CA 仅系统管理员） */
    @PostMapping("/certs/public-key")
    public Result<CertVo> issueWithPublicKey(@RequestBody com.csssl.dto.PublicKeyIssueRequest req,
                                             @RequestParam(required = false) String orgKey,
                                             HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        String org = RoleCode.effectiveOrg(request, orgKey);
        checkSubCaAndTemplate(req.getTemplateId(), req.getCertType(), org, request);
        String username = RoleCode.currentUser(request);
        return Result.success("公钥签发成功", certService.issueWithPublicKey(org, req, null, username, clientIp(request)));
    }

    /** 吊销证书（需 CA操作员/系统管理员；写操作必须落在操作者域，跨机构 403） */
    @PostMapping("/certs/{id}/revoke")
    public Result<CertVo> revoke(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                 @RequestParam(required = false) String orgKey,
                                 HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        requireWriteScope(id, orgKey, request);
        String username = RoleCode.currentUser(request);
        String reason = body == null ? null : body.get("reason");
        return Result.success("吊销成功", certService.revoke(id, reason, username, clientIp(request)));
    }

    /** 挂起证书（GM/T 0034 §12.6：暂停使用，CRL certificateHold / OCSP on-hold；需操作员/管理员；域内写） */
    @PostMapping("/certs/{id}/suspend")
    public Result<CertVo> suspend(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                  @RequestParam(required = false) String orgKey,
                                  HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        requireWriteScope(id, orgKey, request);
        String username = RoleCode.currentUser(request);
        String reason = body == null ? null : body.get("reason");
        return Result.success("已挂起（可解除）", certService.suspend(id, reason, username, clientIp(request)));
    }

    /** 解除挂起（GM/T 0034 §12.7：恢复有效；需操作员/管理员；域内写） */
    @PostMapping("/certs/{id}/unsuspend")
    public Result<CertVo> unsuspend(@PathVariable Long id,
                                    @RequestParam(required = false) String orgKey,
                                    HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        requireWriteScope(id, orgKey, request);
        return Result.success("已解除挂起，恢复有效",
                certService.unsuspend(id, RoleCode.currentUser(request), clientIp(request)));
    }

    /** 证书更新（§12.2：原证吊销 + 同公钥重签延长有效期；需操作员/管理员；机构版签发入口） */
    @PostMapping("/certs/{id}/renew")
    public Result<CertVo> renew(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                @RequestParam(required = false) String orgKey,
                                HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        String org = requireWriteScope(id, orgKey, request);
        Integer days = body == null || body.get("validityDays") == null
                ? null : Integer.valueOf(body.get("validityDays"));
        return Result.success("证书已更新（原证书吊销 SUPERSEDED）",
                certService.renew(org, id, days, RoleCode.currentUser(request), clientIp(request)));
    }

    /** 密钥更新 rekey（§12.2：新密钥对重签，原证吊销；仅限CA侧持有私钥的证书；需操作员/管理员；机构版入口） */
    @PostMapping("/certs/{id}/rekey")
    public Result<CertVo> rekey(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                @RequestParam(required = false) String orgKey,
                                HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        String org = requireWriteScope(id, orgKey, request);
        Integer days = body == null || body.get("validityDays") == null
                ? null : Integer.valueOf(body.get("validityDays"));
        return Result.success("密钥已更新（新证书签发，原证书吊销）",
                certService.rekey(org, id, days, RoleCode.currentUser(request), clientIp(request)));
    }

    /** 下载证书 PEM（公钥证书；需 操作员/管理员，落审计；行级读按数据域收敛） */
    @GetMapping("/certs/{id}/cert.pem")
    public String downloadCert(@PathVariable Long id, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        CaCert row = certService.findById(id);
        RoleCode.requireRowVisible(request, row.getOrgKey());
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "下载证书：" + row.getSubjectCn(), clientIp(request), RoleCode.orgOfRow(row.getOrgKey()));
        return certService.certPem(id);
    }

    /** 下载私钥 PEM（需 管理员/密钥管理员；受 allow-private-key-download 开关控制，落审计；行级读按数据域收敛） */
    @GetMapping("/certs/{id}/key.pem")
    public String downloadKey(@PathVariable Long id, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        CaCert row = certService.findById(id);
        RoleCode.requireRowVisible(request, row.getOrgKey());
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "下载私钥：" + row.getSubjectCn(), clientIp(request), RoleCode.orgOfRow(row.getOrgKey()));
        return certService.keyPem(id);
    }

    /**
     * 导出 PKCS#12（软件证书场景，PBE 口令加密；需 管理员/密钥管理员，落审计）。
     * 口令走 POST body 而非 query 参数，避免进入访问日志/Referer 留痕。
     * 数据域：机构用户仅本机构证书；全局用户跨机构导出放行（密钥介质导出属读取行为，行级归属留审计）。
     */
    @PostMapping("/certs/{id}/export/p12")
    public ResponseEntity<byte[]> exportP12(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, String> body,
                                            HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        CaCert row = certService.findById(id);
        RoleCode.requireRowVisible(request, row.getOrgKey());
        String password = body == null ? null : body.get("password");
        byte[] p12 = certService.exportP12(id, password);
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "导出PKCS#12（口令加密）：" + row.getSubjectCn(), clientIp(request),
                RoleCode.orgOfRow(row.getOrgKey()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-pkcs12"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cert-" + id + ".p12\"")
                .body(p12);
    }

    /** 概览统计（仪表盘；跨机构汇总只读） */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success("查询成功", certService.overview());
    }

    /**
     * RA 直发接口收紧：目标模板（或直传类型）为 SUB_CA 时仅 ADMIN 可发（OPERATOR 不够，
     * 防止操作员越权签发可继续签发证书的中间CA）；带模板ID时先校验模板存在、未被删除
     * 且对签发域可见（全局模板或本机构模板，模板可见集口径）。
     */
    private void checkSubCaAndTemplate(Long templateId, String reqCertType, String org, HttpServletRequest request) {
        if (templateId != null) {
            CertTemplate tpl = templateService.detailVisible(templateId, org);
            if ("SUB_CA".equalsIgnoreCase(tpl.getCertType())) {
                RoleCode.require(request, RoleCode.ADMIN);
            }
            return;
        }
        if ("SUB_CA".equalsIgnoreCase(reqCertType)) {
            RoleCode.require(request, RoleCode.ADMIN);
        }
    }

    /** 写操作域校验：effectiveOrg 与证书行归属机构一致，否则 403（机构用户锁本机构；全局用户须显式传对 orgKey） */
    private String requireWriteScope(Long id, String reqOrgKey, HttpServletRequest request) {
        CaCert row = certService.findById(id);
        return RoleCode.requireWriteOrg(request, reqOrgKey, row.getOrgKey());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
