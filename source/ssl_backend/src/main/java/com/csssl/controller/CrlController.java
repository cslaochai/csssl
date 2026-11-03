package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.vo.CertVO;
import com.csssl.dto.CrlRecordQueryDTODTO;
import com.csssl.vo.CrlViewVO;
import com.csssl.vo.DeltaCrlVO;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCompany;
import com.csssl.service.AuditService;
import com.csssl.service.CrlService;
import com.csssl.service.CompanyService;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 吊销列表（CRL）接口：管理端查看吊销记录与下载 CRL。
 * 路线A多公司：管理端支持 ?companyUscc= 切域查看（缺省 default，行为与单根时代一致）。
 * 只读视图不因公司 SUSPENDED/CLOSED 关闭——历史证书的吊销信息必须持续可得（合规点）。
 */
@RestController
@RequestMapping("/admin/crl")
@RequiredArgsConstructor
public class CrlController {

    private final CrlService crlService;
    private final AuditService auditService;
    private final CompanyService companyService;

    /** 吊销记录 + 当前 CRL PEM + thisUpdate/nextUpdate（管理页；?companyUscc= 切公司域，缺省 default） */
    @GetMapping
    public Result<CrlViewVO> view(@RequestParam(required = false) String companyUscc) {
        String company = resolveCompany(companyUscc);
        List<CaCert> revoked = crlService.revokedList(company);
        CrlService.CrlInfo info = crlService.publishIfNeeded(company);
        CrlViewVO vo = new CrlViewVO();
        // default 域响应体不加 companyUscc 字段（存量 URL 逐字节兼容）；非 default 标注归属域
        if (!CaCompany.LEGACY_NAMESPACE.equals(company)) {
            vo.setCompanyUscc(company);
        }
        vo.setRevoked(revoked.stream().map(CertVO::from).toList());
        vo.setCrlPem(revoked.isEmpty() ? "" : info.pem());
        vo.setThisUpdate(revoked.isEmpty() ? null : info.thisUpdate());
        vo.setNextUpdate(revoked.isEmpty() ? null : info.nextUpdate());
        vo.setEntryCount(info.entries());
        vo.setIssuerCn(info.issuerCn());
        return Result.success("查询成功", vo);
    }

    /** 吊销记录分页查询（管理页表格；keyword=CN/序列号，status=REVOKED/FROZEN，reason=吊销原因） */
    @GetMapping("/records")
    public Result<Page<CertVO>> records(@Valid CrlRecordQueryDTODTO query) {
        return Result.success("查询成功", crlService.recordsPage(resolveCompany(query.getCompanyUscc()), query.getKeyword(),
                query.getStatus(), query.getReason(), query.getPageNo(), query.getPageSize()));
    }

    /** 下载 CRL PEM（判重发布：内容未变直接复用已发布 CRL，不自增编号；?companyUscc= 切公司域） */
    @PreAuthorize("hasAuthority('crl:download')")
    @GetMapping("/download")
    public String download(@RequestParam(required = false) String companyUscc, HttpServletRequest request) {
        String company = resolveCompany(companyUscc);
        String crlPem = crlService.publishIfNeeded(company).pem();
        auditService.record(UserUtils.requireLoginUser(), "CRL_DOWNLOAD", null,
                "下载CRL吊销列表" + (CaCompany.LEGACY_NAMESPACE.equals(company) ? "" : "（公司 " + company + "）"), clientIp(request));
        return crlPem;
    }

    /** 增量 CRL（dCRL）：指定公司域自最近全量发布以来新增的吊销/挂起条目，供依赖方增量同步 */
    @GetMapping("/delta")
    public Result<DeltaCrlVO> delta(@RequestParam(required = false) String companyUscc) {
        String company = resolveCompany(companyUscc);
        CrlService.DeltaCrlInfo info = crlService.buildDeltaCrl(company);
        DeltaCrlVO vo = new DeltaCrlVO();
        if (!CaCompany.LEGACY_NAMESPACE.equals(company)) {
            vo.setCompanyUscc(company);
        }
        vo.setDeltaPem(info.pem());
        vo.setThisUpdate(info.thisUpdate());
        vo.setNextUpdate(info.nextUpdate());
        vo.setEntryCount(info.entries());
        vo.setCrlNumber(info.crlNumber());
        vo.setBaseCrlNumber(info.baseCrlNumber());
        return Result.success("查询成功", vo);
    }

    /** companyUscc 缺省=default（存量行为）；显式传入时公司必须存在（非法标识/不存在按现有 IAE 口径回 4xx） */
    private String resolveCompany(String companyUscc) {
        if (companyUscc == null || companyUscc.isBlank()) {
            return CaCompany.PRIMARY_COMPANY_USCC;
        }
        if (companyService.findByUscc(companyUscc) == null) {
            throw new IllegalArgumentException("公司不存在: " + companyUscc);
        }
        return companyUscc;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
