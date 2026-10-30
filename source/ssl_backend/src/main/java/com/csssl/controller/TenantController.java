package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.TenantUpsertDTO;
import com.csssl.vo.TenantCreateVO;
import com.csssl.vo.TenantDetailVO;
import com.csssl.dto.TenantPageQueryDTO;
import com.csssl.vo.TenantVO;
import com.csssl.entity.CaTenant;
import com.csssl.service.AuditService;
import com.csssl.service.TenantService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户域管理接口（需登录）：租户列表/详情、开租户（登记即建独立根CA）、暂停/恢复/关闭。
 * 新建与状态变更仅系统管理员；审计暂记 default 归属（审计带 tenant 列由下一波接入）。
 */
@RestController
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final AuditService auditService;

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
    @GetMapping("/{tenantKey}")
    public Result<TenantDetailVO> detail(@PathVariable String tenantKey) {
        CaTenant tenant = tenantService.get(tenantKey);
        TenantDetailVO vo = TenantDetailVO.from(tenant);
        vo.setRootStatus(tenantService.overviewOf(tenant.getTenantKey()));
        return Result.success("查询成功", vo);
    }

    /** 新建租户（仅 ADMIN）：登记后立即为该租户生成独立根CA+SUB_CA+Shamir(3,5) 门限份额 */
    @PreAuthorize("hasAuthority('tenant:create')")
    @PostMapping
    public Result<TenantCreateVO> create(@Valid @RequestBody TenantUpsertDTO body, HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        CaTenant tenant = tenantService.create(body.getTenantKey(), body.getTenantName(), body.getSubjectO(),
                body.getAlgorithm(), body.getContact(), body.getDescription());
        auditService.record(username, "TENANT_CREATE", tenant.getTenantKey(),
                "新建租户 " + tenant.getTenantName() + "（主体O=" + tenant.getSubjectO() + "，算法=" + tenant.getAlgorithm()
                        + "），已初始化独立根CA与SUB_CA", clientIp(request));
        TenantCreateVO vo = new TenantCreateVO();
        vo.setTenantKey(tenant.getTenantKey());
        vo.setRootStatus(tenantService.overviewOf(tenant.getTenantKey()));
        return Result.success("租户已创建并完成独立根CA初始化（请尽快导存 5 份门限份额）", vo);
    }

    /** 暂停租户（仅 ADMIN；停止对外签发由后续波次在签发链路 enforce，本波落状态） */
    @PreAuthorize("hasAuthority('tenant:suspend')")
    @PostMapping("/{tenantKey}/suspend")
    public Result<CaTenant> suspend(@PathVariable String tenantKey, HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        CaTenant tenant = tenantService.suspend(tenantKey);
        auditService.record(username, "TENANT_SUSPEND", tenantKey, "暂停租户 " + tenant.getTenantName(), clientIp(request));
        return Result.success("租户已暂停", tenant);
    }

    /** 恢复租户（仅 ADMIN） */
    @PreAuthorize("hasAuthority('tenant:resume')")
    @PostMapping("/{tenantKey}/resume")
    public Result<CaTenant> resume(@PathVariable String tenantKey, HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        CaTenant tenant = tenantService.resume(tenantKey);
        auditService.record(username, "TENANT_RESUME", tenantKey, "恢复租户 " + tenant.getTenantName(), clientIp(request));
        return Result.success("租户已恢复", tenant);
    }

    /** 关闭租户（仅 ADMIN；CLOSED 终态） */
    @PreAuthorize("hasAuthority('tenant:close')")
    @PostMapping("/{tenantKey}/close")
    public Result<CaTenant> close(@PathVariable String tenantKey, HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        CaTenant tenant = tenantService.close(tenantKey);
        auditService.record(username, "TENANT_CLOSE", tenantKey, "关闭租户 " + tenant.getTenantName(), clientIp(request));
        return Result.success("租户已关闭", tenant);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
