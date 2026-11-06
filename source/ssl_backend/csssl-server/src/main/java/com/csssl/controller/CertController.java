package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.*;
import com.csssl.entity.CaCert;
import com.csssl.service.AuditService;
import com.csssl.service.CertService;
import com.csssl.util.UserUtils;
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
 * 证书管理接口（需登录）：列表/详情/下载/导出/概览。
 *
 * <p><b>本控制器不提供任何直接签发接口。</b>发证只有两条合法路径：
 * <ul>
 *   <li>RA 证书申请：{@code /admin/applications} 提交 → 被指派审批人批准 → 持 {@code app:issue} 按单签发；</li>
 *   <li>二级CA：{@code /admin/ca/subca-rebuild} 由 5 名密钥保管人 3-of-5 审批后用根CA 重签。</li>
 * </ul>
 * 直发会绕开双人复核，也绕开申请单里的主体/模板快照，因此端点、服务方法与权限点（cert:issue）一并删除，
 * 对外 API 通道（/ca/api/v1）同样只有申请/撤销/吊销，不含签发。</p>
 *
 * <p>路线A数据域（服务端强制，effectiveCompany 口径）：
 * <ul>
 *   <li>列表：公司用户强制本公司过滤；全局用户可按 body.companyUscc= 过滤，不传=跨公司全量只读（唯一读口，无写旁路）。</li>
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

    /**
     * 证书列表（分页 + 类型/状态/关键字/公司筛选；需 操作员/管理员/密钥管理员）
     */
    @PreAuthorize("hasAuthority('cert:list')")
    @PostMapping("/certs/page")
    public Result<Page<CertVO>> page(@Valid @RequestBody CertPageQueryDTODTO query, HttpServletRequest request) {
        // 公司用户锁本公司（显式传别公司 403）；全局用户不传=null=跨公司全量只读
        String scope = query.getCompanyUscc();
        return Result.success("查询成功", certService.page(query.getPageNo(), query.getPageSize(),
                query.getCertType(), query.getStatus(), query.getKeyword(), scope));
    }

    /**
     * 证书详情（公司用户仅本公司证书行，越域 403；全局用户跨公司只读放行）
     */
    @PostMapping("/certs/detail")
    public Result<CertVO> detail(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", certService.detailVisible(body.getId(), null));
    }

    /**
     * 下载证书 PEM（公钥证书；需 操作员/管理员，落审计；行级读按数据域收敛）
     */
    @PreAuthorize("hasAuthority('cert:download')")
    @PostMapping("/certs/cert-pem")
    public String downloadCert(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        CaCert row = certService.findById(body.getId());
        auditService.record(UserUtils.requireLoginUser(), "CERT_DOWNLOAD", String.valueOf(body.getId()),
                "下载证书：" + row.getSubjectCn(), clientIp(request), row.getCompanyUscc());
        return certService.certPem(body.getId());
    }

    /**
     * 导出证书压缩包（ZIP，不加密）：内含证书链 PEM，供 HIS 等外部系统「上传 zip 回填证书」导入。
     *
     * <p>与 cert.pem 同一数据域与审计口径；不设口令（口令需线下传递反而抬高导入门槛），
     * 私钥不出 CA，包内仅公钥证书链。
     */
    @PreAuthorize("hasAuthority('cert:download')")
    @PostMapping("/certs/export-zip")
    public ResponseEntity<byte[]> exportCertZip(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        CaCert row = certService.findById(body.getId());
        byte[] zip = certService.certZipBytes(body.getId());
        auditService.record(UserUtils.requireLoginUser(), "CERT_DOWNLOAD", String.valueOf(body.getId()),
                "导出证书压缩包(ZIP)：" + row.getSubjectCn(), clientIp(request),
                row.getCompanyUscc());
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
    @PostMapping("/certs/key-pem")
    public String downloadKey(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        CaCert row = certService.findById(body.getId());
        auditService.record(UserUtils.requireLoginUser(), "CERT_DOWNLOAD", String.valueOf(body.getId()),
                "下载私钥：" + row.getSubjectCn(), clientIp(request), row.getCompanyUscc());
        return certService.keyPem(body.getId());
    }

    /**
     * 导出 PKCS#12（软件证书场景，PBE 口令加密；需 管理员/密钥管理员，落审计）。
     * 口令走 POST body 而非 query 参数，避免进入访问日志/Referer 留痕。
     * 数据域：公司用户仅本公司证书；全局用户跨公司导出放行（密钥介质导出属读取行为，行级归属留审计）。
     */
    @PreAuthorize("hasAuthority('cert:export')")
    @PostMapping("/certs/export-p12")
    public ResponseEntity<byte[]> exportP12(@RequestBody CertExportP12DTO body,
                                            HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        CaCert row = certService.findById(body.getId());
        String password = body.getPassword();
        byte[] p12 = certService.exportP12(body.getId(), password);
        auditService.record(UserUtils.requireLoginUser(), "CERT_DOWNLOAD", String.valueOf(body.getId()),
                "导出PKCS#12（口令加密）：" + row.getSubjectCn(), clientIp(request),
                row.getCompanyUscc());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-pkcs12"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cert-" + body.getId() + ".p12\"")
                .body(p12);
    }

    /**
     * 概览统计（仪表盘；按当前公司域过滤，公司用户锁本公司）
     */
    @PostMapping("/overview")
    public Result<CertOverviewVO> overview(@RequestBody(required = false) CompanyUsccDTO body,
                                           HttpServletRequest request) {
        String companyUscc = body == null ? null : body.getCompanyUscc();
        return Result.success("查询成功", certService.overview(companyUscc));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
