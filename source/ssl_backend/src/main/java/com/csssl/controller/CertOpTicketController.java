package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.ApplicationAuditDTO;
import com.csssl.dto.CertOpPageQueryDTO;
import com.csssl.dto.CertOpSubmitDTO;
import com.csssl.entity.CaCertOpTicket;
import com.csssl.entity.LoginUser;
import com.csssl.service.CertOpTicketService;
import com.csssl.service.PermissionService;
import com.csssl.util.PermissionCatalog;
import com.csssl.util.TenantScope;
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

/**
 * 证书操作审批单接口（吊销/挂起/解除挂起/更新/重签双人复核）：
 * 发起=持 cert:revoke / cert:suspend / cert:renew / cert:rekey 权限者（按操作类型分权），
 * 审批=持 cert:op:approve 权限者（不得与发起人同一人），批准即执行进 CRL/OCSP 或重签新证。
 * 直接吊销/挂起/更新/重签端点已拆除，含系统管理员在内一律走单。
 */
@RestController
@RequestMapping("/admin/cert-ops")
@RequiredArgsConstructor
public class CertOpTicketController {

    private final CertOpTicketService ticketService;
    private final PermissionService permissionService;

    /** 操作单分页查询（登录即可查看；租户用户强制本租户，全局用户可按 ?tenantKey= 过滤，不传=全量只读） */
    @GetMapping
    public Result<Page<CaCertOpTicket>> page(@Valid CertOpPageQueryDTO query, HttpServletRequest request) {
        return Result.success("查询成功", ticketService.page(query.getPageNo(), query.getPageSize(),
                query.getStatus(), query.getOpType(), query.getKeyword(),
                TenantScope.readTenantScope(UserUtils.requireLoginUser().getTenantKey(), query.getTenantKey())));
    }

    /** 操作单详情（租户用户访问他租户单按不存在处理） */
    @GetMapping("/{id}")
    public Result<CaCertOpTicket> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("查询成功",
                ticketService.detailVisible(id, UserUtils.requireLoginUser().getTenantKey()));
    }

    /**
     * 发起操作单（不执行，仅落待审批）。权限按操作类型分权：
     * 吊销需 cert:revoke，挂起/解除挂起需 cert:suspend，更新/重签需 cert:renew / cert:rekey。
     */
    @PostMapping
    public Result<CaCertOpTicket> submit(@Valid @RequestBody CertOpSubmitDTO body,
                                         @RequestParam(required = false) String tenantKey,
                                         HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        if (CertOpTicketService.OP_REVOKE.equals(body.getOpType())) {
            permissionService.require(request, PermissionCatalog.CERT_REVOKE);
        } else if (CertOpTicketService.OP_SUSPEND.equals(body.getOpType())
                || CertOpTicketService.OP_UNSUSPEND.equals(body.getOpType())) {
            permissionService.require(request, PermissionCatalog.CERT_SUSPEND);
        } else if (CertOpTicketService.OP_RENEW.equals(body.getOpType())) {
            permissionService.require(request, PermissionCatalog.CERT_RENEW);
        } else if (CertOpTicketService.OP_REKEY.equals(body.getOpType())) {
            permissionService.require(request, PermissionCatalog.CERT_REKEY);
        }
        String effTenant = TenantScope.requireExplicitTenant(loginUser.getTenantKey(),
                tenantKey == null || tenantKey.isBlank() ? body.getTenantKey() : tenantKey);
        return Result.success("操作单已提交，待审批", ticketService.submit(body.getOpType(), body.getCertId(),
                body.getReason(), body.getValidityDays(),
                loginUser.getUsername(), loginUser.getRealName(), clientIp(request), effTenant));
    }

    /** 审批批准（不得与发起人同一人；批准即执行：证书状态流转 + CRL/OCSP 生效） */
    @PreAuthorize("hasAuthority('cert:op:approve')")
    @PostMapping("/{id}/approve")
    public Result<CaCertOpTicket> approve(@PathVariable Long id,
                                          @Valid @RequestBody(required = false) ApplicationAuditDTO body,
                                          @RequestParam(required = false) String tenantKey,
                                          HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String comment = body == null ? null : body.getComment();
        return Result.success("已批准并执行", ticketService.approve(id, comment,
                loginUser.getUsername(), loginUser.getRealName(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    /** 审批驳回（不得与发起人同一人；意见必填；证书状态不变） */
    @PreAuthorize("hasAuthority('cert:op:approve')")
    @PostMapping("/{id}/reject")
    public Result<CaCertOpTicket> reject(@PathVariable Long id, @Valid @RequestBody ApplicationAuditDTO body,
                                         @RequestParam(required = false) String tenantKey,
                                         HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已驳回", ticketService.reject(id, body.getComment(),
                loginUser.getUsername(), loginUser.getRealName(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    /** 撤销操作单（仅发起人本人或系统管理员；仅待审批可撤） */
    @PostMapping("/{id}/cancel")
    public Result<CaCertOpTicket> cancel(@PathVariable Long id, @RequestParam(required = false) String tenantKey,
                                         HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已撤销", ticketService.cancel(id,
                loginUser.getUsername(), loginUser.getRoleCode(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
