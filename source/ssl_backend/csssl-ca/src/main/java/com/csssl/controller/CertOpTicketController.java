package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.ApplicationAuditDTO;
import com.csssl.dto.CertOpPageQueryDTO;
import com.csssl.dto.CertOpSubmitDTO;
import com.csssl.dto.IdDTO;
import com.csssl.dto.IdCompanyUsccDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.CaCertOpTicket;
import com.csssl.entity.LoginUser;
import com.csssl.enums.CertOpTypeEnum;
import com.csssl.service.CertOpTicketService;
import com.csssl.service.PermissionService;
import com.csssl.util.PermissionCatalog;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /**
     * 操作单分页查询（登录即可查看；公司用户强制本公司，全局用户可按 body.companyUscc= 过滤，不传=全量只读）
     */
    @PostMapping("/page")
    public Result<Page<CaCertOpTicket>> page(@Valid @RequestBody CertOpPageQueryDTO query, HttpServletRequest request) {
        LoginUser viewer = UserUtils.requireLoginUser();
        return Result.success("查询成功", ticketService.page(query.getPageNo(), query.getPageSize(),
                query.getStatus(), query.getOpType(), query.getKeyword(),
                query.getCompanyUscc(), viewer));
    }

    /**
     * 操作单详情（公司用户访问他公司单按不存在处理）
     */
    @PostMapping("/detail")
    public Result<CaCertOpTicket> detail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", ticketService.detailVisible(body.getId(), UserUtils.requireLoginUser()));
    }

    /**
     * 发起操作单（不执行，仅落待审批）。权限按操作类型分权：
     * 吊销需 cert:revoke，挂起/解除挂起需 cert:suspend，更新/重签需 cert:renew / cert:rekey。
     */
    @PostMapping("/submit")
    public Result<CaCertOpTicket> submit(@Valid @RequestBody CertOpSubmitDTO submitDTO,
                                         HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        CertOpTypeEnum opType = CertOpTypeEnum.fromCode(submitDTO.getOpType());
        if (opType == CertOpTypeEnum.REVOKE) {
            permissionService.require(request, PermissionCatalog.CERT_REVOKE);
        } else if (opType == CertOpTypeEnum.SUSPEND || opType == CertOpTypeEnum.UNSUSPEND) {
            permissionService.require(request, PermissionCatalog.CERT_SUSPEND);
        } else if (opType == CertOpTypeEnum.RENEW) {
            permissionService.require(request, PermissionCatalog.CERT_RENEW);
        } else if (opType == CertOpTypeEnum.REKEY) {
            permissionService.require(request, PermissionCatalog.CERT_REKEY);
        }
        String effCompany = submitDTO.getCompanyUscc();
        return Result.success("操作单已提交，待审批", ticketService.submit(submitDTO.getOpType(), submitDTO.getCertId(),
                submitDTO.getReason(), submitDTO.getValidityDays(), submitDTO.getAssigneeUserId(),
                loginUser, clientIp(request), effCompany));
    }

    /**
     * 审批批准（不得与发起人同一人；批准即执行：证书状态流转 + CRL/OCSP 生效）
     */
    @PreAuthorize("hasAuthority('cert:op:approve')")
    @PostMapping("/approve")
    public Result<CaCertOpTicket> approve(@Valid @RequestBody ApplicationAuditDTO body,
                                          HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已批准并执行", ticketService.approve(body.getId(), body.getComment(),
                loginUser, clientIp(request),
                body.getCompanyUscc()));
    }

    /**
     * 审批驳回（不得与发起人同一人；意见必填；证书状态不变）
     */
    @PreAuthorize("hasAuthority('cert:op:approve')")
    @PostMapping("/reject")
    public Result<CaCertOpTicket> reject(@Valid @RequestBody ApplicationAuditDTO body,
                                         HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已驳回", ticketService.reject(body.getId(), body.getComment(),
                loginUser, clientIp(request),
                body.getCompanyUscc()));
    }

    /**
     * 撤销操作单（仅发起人本人或系统管理员；仅待审批可撤）
     */
    @PostMapping("/cancel")
    public Result<CaCertOpTicket> cancel(@RequestBody IdCompanyUsccDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已撤销", ticketService.cancel(body.getId(),
                loginUser, clientIp(request),
                body.getCompanyUscc()));
    }

    /**
     * 审批轨迹（按步时间正序，面向业务用户）
     */
    @PostMapping("/trail")
    public Result<List<ApplicationTrail>> listTrail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", ticketService.listTrail(body.getId()));
    }

    /**
     * 催办：向被指派审批人推送站内信并补 URGE 轨迹（仅提交人可催办）
     */
    @PreAuthorize("hasAuthority('app:urge')")
    @PostMapping("/urge")
    public Result<Void> urge(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        ticketService.urge(body.getId(), loginUser, clientIp(request));
        return Result.success("已催办", null);
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
