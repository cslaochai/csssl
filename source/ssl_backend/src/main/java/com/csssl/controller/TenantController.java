package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.TenantCreateDTO;
import com.csssl.dto.TenantPageQueryDTO;
import com.csssl.entity.CaTenant;
import com.csssl.entity.LoginUser;
import com.csssl.service.TenantProvisionService;
import com.csssl.service.TenantService;
import com.csssl.util.UserUtils;
import com.csssl.vo.TenantCreateVO;
import com.csssl.vo.TenantDetailVO;
import com.csssl.vo.TenantVO;
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
 * 租户域接口（需登录）：列表/分页/详情 + 新建。
 * 新建租户直接执行（开租户即建独立根CA+SUB_CA 并发放接入凭证，无需双人复核）；
 * 暂停/恢复/关闭/重置接入密钥等敏感变更走 {@link TenantOpTicketController}（/admin/tenant-ops）双人复核。
 */
@RestController
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final TenantProvisionService tenantProvisionService;

    /** 新建租户（直接执行，仅全局账号；返回一次性接入凭证，secret 不落库） */
    @PreAuthorize("hasAuthority('tenant:create')")
    @PostMapping
    public Result<TenantCreateVO> create(@Valid @RequestBody TenantCreateDTO body, HttpServletRequest request) {
        LoginUser user = UserUtils.requireLoginUser();
        if (user.getTenantUscc() != null) {
            throw new SecurityException("权限不足：新建租户仅限全局账号");
        }
        return Result.success("租户已创建，请立即保存接入凭证（密钥仅展示一次）",
                tenantProvisionService.create(body.getUscc(), body.getTenantName(), body.getSubjectO(),
                        body.getAlgorithm(), body.getContact(), body.getContactPhone(), body.getDescription(),
                        user.getUsername(), clientIp(request)));
    }

    /** 租户列表（附各租户根状态：有无根/SUB_CA 有效期/密钥形态 ONLINE/SEALED/SHARES） */
    @GetMapping
    public Result<List<TenantVO>> list() {
        return Result.success("查询成功", tenantService.overview());
    }

    /** 租户分页查询（管理页：keyword 命中 标识/名称/主体O；status 精确） */
    @GetMapping("/page")
    public Result<Page<TenantVO>> page(@Valid TenantPageQueryDTO query) {
        return Result.success("查询成功", tenantService.page(query.getKeyword(), query.getStatus(),
                query.getPageNo(), query.getPageSize()));
    }

    /** 租户详情（含根CA状态与门限份额状态） */
    @GetMapping("/{tenantUscc}")
    public Result<TenantDetailVO> detail(@PathVariable String tenantUscc) {
        CaTenant tenant = tenantService.get(tenantUscc);
        TenantDetailVO vo = TenantDetailVO.from(tenant);
        vo.setRootStatus(tenantService.overviewOf(tenant.getUscc()));
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
