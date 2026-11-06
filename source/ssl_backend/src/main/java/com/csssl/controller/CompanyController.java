package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CompanyCreateDTO;
import com.csssl.dto.CompanyPageQueryDTO;
import com.csssl.dto.CompanyUsccDTO;
import com.csssl.entity.CaCompany;
import com.csssl.entity.LoginUser;
import com.csssl.entity.CompanyKeyShare;
import com.csssl.service.ApiClientService;
import com.csssl.service.CaService;
import com.csssl.service.CaSystemService;
import com.csssl.service.CompanyProvisionService;
import com.csssl.service.CompanyService;
import com.csssl.util.UserUtils;
import com.csssl.vo.CaSubCaHolderVO;
import com.csssl.vo.CaSystemVO;
import com.csssl.vo.CompanyCreateVO;
import com.csssl.vo.CompanyDetailVO;
import com.csssl.vo.CompanyVO;
import com.csssl.vo.ApiClientVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final CaService caService;
    private final CaSystemService caSystemService;
    private final ApiClientService apiClientService;

    /**
     * 新建公司（直接执行，仅全局账号；返回一次性接入凭证，secret 不落库）
     */
    @PreAuthorize("hasAuthority('company:create')")
    @PostMapping("/create")
    public Result<CompanyCreateVO> create(@Valid @RequestBody CompanyCreateDTO body, HttpServletRequest request) {
        LoginUser user = UserUtils.requireLoginUser();
        return Result.success("公司已创建，请立即保存接入凭证（密钥仅展示一次）",
                companyProvisionService.create(body.getUscc(), body.getCompanyName(), body.getSubjectO(),
                        body.getAlgorithm(), body.getContact(), body.getContactPhone(), body.getDescription(),
                        body.getShareHolders(), user, clientIp(request)));
    }

    /**
     * 公司列表（附各公司根状态：有无根/SUB_CA 有效期/密钥形态 ONLINE/SEALED/SHARES）
     */
    @PostMapping("/list")
    public Result<List<CompanyVO>> list() {
        return Result.success("查询成功", companyService.overview());
    }

    /**
     * 公司分页查询（管理页：keyword 命中 标识/名称/主体O；status 精确）
     */
    @PostMapping("/page")
    public Result<Page<CompanyVO>> page(@Valid @RequestBody CompanyPageQueryDTO query) {
        return Result.success("查询成功", companyService.page(query.getKeyword(), query.getStatus(),
                query.getPageNo(), query.getPageSize()));
    }

    /**
     * 公司详情（含根CA状态 + 门限份额状态 + 保管人名单）
     */
    @PostMapping("/detail")
    public Result<CompanyDetailVO> detail(@RequestBody CompanyUsccDTO body) {
        if (body.getCompanyUscc() == null || body.getCompanyUscc().isBlank()) {
            throw new IllegalArgumentException("参数 companyUscc 不能为空");
        }
        CaCompany company = companyService.get(body.getCompanyUscc());
        CompanyDetailVO vo = CompanyDetailVO.from(company);
        vo.setRootStatus(companyService.overviewOf(company.getUscc()));
        // 保管人名单直接从公司档案带出（不含份额明文，无需密钥权限即可查看）
        List<CompanyKeyShare> shares = caService.listKeyShareHolders(company.getUscc());
        vo.setHolders(shares.stream().map(s -> {
            CaSubCaHolderVO h = new CaSubCaHolderVO();
            h.setShareIndex(s.getShareIndex());
            h.setHolderRealName(s.getHolderRealName());
            return h;
        }).collect(Collectors.toList()));
        // 公司下的系统 + 接入凭证（只读巡检；不含 secret）
        vo.setSystems(buildSystemBindings(company.getUscc()));
        return Result.success("查询成功", vo);
    }

    /**
     * 组装公司下的系统与接入凭证一览：以系统为主表，凭证按 systemName 匹配；
     * 未登记凭证的系统也列出（appId 为空）。供管理员只读巡检，无需 apiclient:manage 权限。
     */
    private List<CompanyDetailVO.SystemBindingVO> buildSystemBindings(String uscc) {
        List<CaSystemVO> systems = caSystemService.listByCompany(uscc);
        List<ApiClientVO> clients = apiClientService.list().stream()
                .filter(c -> uscc.equals(c.getCompanyUscc()))
                .collect(Collectors.toList());
        Map<String, ApiClientVO> bySystem = clients.stream()
                .collect(Collectors.toMap(ApiClientVO::getSystemName, c -> c, (a, b) -> a));
        List<CompanyDetailVO.SystemBindingVO> rows = new ArrayList<>();
        for (CaSystemVO s : systems) {
            CompanyDetailVO.SystemBindingVO row = new CompanyDetailVO.SystemBindingVO();
            row.setSystemName(s.getSystemName());
            ApiClientVO client = bySystem.remove(s.getSystemName());
            if (client != null) {
                row.setAppId(client.getAppId());
                row.setStatus(client.getStatus());
                row.setSecretVersion(client.getSecretVersion());
                row.setLastUsedAt(client.getLastUsedAt() == null ? null
                        : client.getLastUsedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            rows.add(row);
        }
        // 存量凭证未归属任何系统（system_name 为空）时也要展示
        for (ApiClientVO client : bySystem.values()) {
            CompanyDetailVO.SystemBindingVO row = new CompanyDetailVO.SystemBindingVO();
            row.setSystemName(client.getClientName());
            row.setAppId(client.getAppId());
            row.setStatus(client.getStatus());
            row.setSecretVersion(client.getSecretVersion());
            row.setLastUsedAt(client.getLastUsedAt() == null ? null
                    : client.getLastUsedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            rows.add(row);
        }
        return rows;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
