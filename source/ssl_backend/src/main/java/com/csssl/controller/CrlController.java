package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.vo.CertVO;
import com.csssl.dto.CrlRecordQueryDTODTO;
import com.csssl.dto.CompanyUsccDTO;
import com.csssl.vo.CrlViewVO;
import com.csssl.vo.DeltaCrlVO;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCompany;
import com.csssl.service.AuditService;
import com.csssl.service.CaSystemService;
import com.csssl.service.CrlService;
import com.csssl.service.CompanyService;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 吊销列表（CRL）接口：管理端查看吊销记录与下载 CRL。
 * 路线A多公司：管理端支持 body.companyUscc= 切域查看（缺省 default，行为与单根时代一致）。
 * 只读视图不因公司 SUSPENDED/CLOSED 关闭——历史证书的吊销信息必须持续可得（合规点）。
 */
@RestController
@RequestMapping("/admin/crl")
@RequiredArgsConstructor
public class CrlController {

    private final CrlService crlService;
    private final AuditService auditService;
    private final CompanyService companyService;
    private final CaSystemService caSystemService;

    /**
     * 吊销记录 + 当前 CRL PEM + thisUpdate/nextUpdate（管理页；body.companyUscc= 切公司域，缺省 default）
     */
    @PostMapping("/view")
    public Result<CrlViewVO> view(@RequestBody(required = false) CompanyUsccDTO body) {
        String companyUscc = body == null ? null : body.getCompanyUscc();
        String company = resolveCompany(companyUscc);
        String system = resolveSystem(company, body == null ? null : body.getSystemName());
        List<CaCert> revoked = crlService.revokedList(company, system);
        CrlService.CrlInfo info = crlService.publishIfNeeded(company, system);
        CrlViewVO vo = new CrlViewVO();
        // default 域响应体不加 companyUscc 字段（存量 URL 逐字节兼容）；非 default 标注归属域与系统
        if (!CaCompany.LEGACY_NAMESPACE.equals(company)) {
            vo.setCompanyUscc(company);
            vo.setSystemName(system);
        }
        vo.setRevoked(revoked.stream().map(CertVO::from).toList());
        vo.setCrlPem(revoked.isEmpty() ? "" : info.pem());
        vo.setThisUpdate(revoked.isEmpty() ? null : info.thisUpdate());
        vo.setNextUpdate(revoked.isEmpty() ? null : info.nextUpdate());
        vo.setEntryCount(info.entries());
        vo.setIssuerCn(info.issuerCn());
        return Result.success("查询成功", vo);
    }

    /**
     * 吊销记录分页查询（管理页表格；keyword=CN/序列号，status=REVOKED/FROZEN，reason=吊销原因）
     */
    @PostMapping("/records")
    public Result<Page<CertVO>> records(@Valid @RequestBody CrlRecordQueryDTODTO query) {
        String company = resolveCompany(query.getCompanyUscc());
        String system = resolveSystem(company, query.getSystemName());
        return Result.success("查询成功", crlService.recordsPage(company, system, query.getKeyword(),
                query.getStatus(), query.getReason(), query.getPageNo(), query.getPageSize()));
    }

    /**
     * 下载 CRL PEM（判重发布：内容未变直接复用已发布 CRL，不自增编号；body.companyUscc= 切公司域）
     */
    @PreAuthorize("hasAuthority('crl:download')")
    @PostMapping("/download")
    public String download(@RequestBody(required = false) CompanyUsccDTO body, HttpServletRequest request) {
        String companyUscc = body == null ? null : body.getCompanyUscc();
        String company = resolveCompany(companyUscc);
        String system = resolveSystem(company, body == null ? null : body.getSystemName());
        String crlPem = crlService.publishIfNeeded(company, system).pem();
        auditService.record(UserUtils.requireLoginUser(), "CRL_DOWNLOAD", null,
                "下载CRL吊销列表" + (CaCompany.LEGACY_NAMESPACE.equals(company) ? "" : "（公司 " + company + "，系统 " + system + "）"), clientIp(request));
        return crlPem;
    }

    /**
     * 增量 CRL（dCRL）：指定公司域自最近全量发布以来新增的吊销/挂起条目，供依赖方增量同步
     */
    @PostMapping("/delta")
    public Result<DeltaCrlVO> delta(@RequestBody(required = false) CompanyUsccDTO body) {
        String companyUscc = body == null ? null : body.getCompanyUscc();
        String company = resolveCompany(companyUscc);
        String system = resolveSystem(company, body == null ? null : body.getSystemName());
        CrlService.DeltaCrlInfo info = crlService.buildDeltaCrl(company, system);
        DeltaCrlVO vo = new DeltaCrlVO();
        if (!CaCompany.LEGACY_NAMESPACE.equals(company)) {
            vo.setCompanyUscc(company);
            vo.setSystemName(system);
        }
        vo.setDeltaPem(info.pem());
        vo.setThisUpdate(info.thisUpdate());
        vo.setNextUpdate(info.nextUpdate());
        vo.setEntryCount(info.entries());
        vo.setCrlNumber(info.crlNumber());
        vo.setBaseCrlNumber(info.baseCrlNumber());
        return Result.success("查询成功", vo);
    }

    /**
     * companyUscc 缺省=default（存量行为）；显式传入时公司必须存在（非法标识/不存在按现有 IAE 口径回 4xx）
     */
    private String resolveCompany(String companyUscc) {
        if (companyUscc == null || companyUscc.isBlank()) {
            return "default";
        }
        if (companyService.findByUscc(companyUscc) == null) {
            throw new IllegalArgumentException("公司不存在: " + companyUscc);
        }
        return companyUscc;
    }

    /**
     * 系统名解析：body 显式携带则直接用；缺省回落到该公司首个系统名（与签发口径一致，保证必有 SUB_CA 域 CRL）
     */
    private String resolveSystem(String companyUscc, String systemName) {
        if (systemName != null && !systemName.isBlank()) {
            return systemName;
        }
        return caSystemService.firstSystemName(companyUscc);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
