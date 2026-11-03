package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CompanyCreateDTO;
import com.csssl.dto.CompanyPageQueryDTO;
import com.csssl.entity.CaCompany;
import com.csssl.entity.LoginUser;
import com.csssl.service.CompanyProvisionService;
import com.csssl.service.CompanyService;
import com.csssl.util.UserUtils;
import com.csssl.vo.CompanyCreateVO;
import com.csssl.vo.CompanyDetailVO;
import com.csssl.vo.CompanyVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公司域接口（需登录）：列表/分页/详情 + 新建。
 * 新建公司直接执行（开公司即建独立根CA+SUB_CA 并发放接入凭证，无需双人复核）；
 * 暂停/恢复/关闭/重置接入密钥等敏感变更走 {@link CompanyOpTicketController}（/admin/company-ops）双人复核。
 */
@RestController
@RequestMapping("/admin/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyProvisionService companyProvisionService;

    /** 新建公司（直接执行，仅全局账号；返回一次性接入凭证，secret 不落库） */
    @PreAuthorize("hasAuthority('company:create')")
    @PostMapping
    public Result<CompanyCreateVO> create(@Valid @RequestBody CompanyCreateDTO body, HttpServletRequest request) {
        LoginUser user = UserUtils.requireLoginUser();
        return Result.success("公司已创建，请立即保存接入凭证（密钥仅展示一次）",
                companyProvisionService.create(body.getUscc(), body.getCompanyName(), body.getSubjectO(),
                        body.getAlgorithm(), body.getContact(), body.getContactPhone(), body.getDescription(),
                        body.getShareHolders(), user, clientIp(request)));
    }

    /** 公司列表（附各公司根状态：有无根/SUB_CA 有效期/密钥形态 ONLINE/SEALED/SHARES） */
    @GetMapping
    public Result<List<CompanyVO>> list() {
        return Result.success("查询成功", companyService.overview());
    }

    /** 公司分页查询（管理页：keyword 命中 标识/名称/主体O；status 精确） */
    @GetMapping("/page")
    public Result<Page<CompanyVO>> page(@Valid CompanyPageQueryDTO query) {
        return Result.success("查询成功", companyService.page(query.getKeyword(), query.getStatus(),
                query.getPageNo(), query.getPageSize()));
    }

    /** 公司详情（含根CA状态与门限份额状态） */
    @GetMapping("/{companyUscc}")
    public Result<CompanyDetailVO> detail(@PathVariable String companyUscc) {
        CaCompany company = companyService.get(companyUscc);
        CompanyDetailVO vo = CompanyDetailVO.from(company);
        vo.setRootStatus(companyService.overviewOf(company.getUscc()));
        return Result.success("查询成功", vo);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
