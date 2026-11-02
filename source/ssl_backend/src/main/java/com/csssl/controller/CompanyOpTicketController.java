package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.ApplicationAuditDTO;
import com.csssl.dto.CompanyOpPageQueryDTO;
import com.csssl.dto.CompanyOpSubmitDTO;
import com.csssl.entity.CompanyOpTicket;
import com.csssl.entity.LoginUser;
import com.csssl.service.CompanyOpTicketService;
import com.csssl.util.UserUtils;
import com.csssl.vo.CompanyOpReceiptVO;
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

/**
 * 公司生命周期变更审批单接口（暂停/恢复/关闭/重置接入密钥走单双人复核）：
 * 发起=持 company:op:apply 的公司管理员，审批=持 company:op:approve 的公司审批员（不得与发起人同一人），
 * 批准即执行。新建公司不属敏感变更，已改为 {@link CompanyController} 直接执行，本接口不再接受 CREATE 新单。
 * 全部端点仅限全局账号（公司账号不参与平台级公司生命周期）。
 */
@RestController
@RequestMapping("/admin/company-ops")
@RequiredArgsConstructor
public class CompanyOpTicketController {

    private final CompanyOpTicketService ticketService;

    /** 变更单分页查询（全局账号；只读，系统管理员可巡检） */
    @GetMapping
    public Result<Page<CompanyOpTicket>> page(@Valid CompanyOpPageQueryDTO query) {
        requireGlobal();
        return Result.success("查询成功", ticketService.page(query.getPageNo(), query.getPageSize(),
                query.getStatus(), query.getOpType(), query.getKeyword()));
    }

    /** 变更单详情 */
    @GetMapping("/{id}")
    public Result<CompanyOpTicket> detail(@PathVariable Long id) {
        requireGlobal();
        return Result.success("查询成功", ticketService.detail(id));
    }

    /** 发起变更单（不执行，仅落待审批） */
    @PreAuthorize("hasAuthority('company:op:apply')")
    @PostMapping
    public Result<CompanyOpTicket> submit(@Valid @RequestBody CompanyOpSubmitDTO body, HttpServletRequest request) {
        LoginUser user = requireGlobal();
        return Result.success("变更单已提交，待审批", ticketService.submit(body,
                user.getUsername(), user.getRealName(), clientIp(request)));
    }

    /** 审批批准（不得与发起人同一人；批准即执行：状态流转/密钥轮换） */
    @PreAuthorize("hasAuthority('company:op:approve')")
    @PostMapping("/{id}/approve")
    public Result<CompanyOpTicket> approve(@PathVariable Long id,
                                            @Valid @RequestBody(required = false) ApplicationAuditDTO body,
                                            HttpServletRequest request) {
        LoginUser user = requireGlobal();
        String comment = body == null ? null : body.getComment();
        return Result.success("已批准并执行", ticketService.approve(id, comment,
                user.getUsername(), user.getRealName(), clientIp(request)));
    }

    /** 审批驳回（不得与发起人同一人；意见必填；公司状态不变） */
    @PreAuthorize("hasAuthority('company:op:approve')")
    @PostMapping("/{id}/reject")
    public Result<CompanyOpTicket> reject(@PathVariable Long id, @Valid @RequestBody ApplicationAuditDTO body,
                                           HttpServletRequest request) {
        LoginUser user = requireGlobal();
        return Result.success("已驳回", ticketService.reject(id, body.getComment(),
                user.getUsername(), user.getRealName(), clientIp(request)));
    }

    /** 撤回变更单（仅发起人本人；仅待审批可撤） */
    @PostMapping("/{id}/cancel")
    public Result<CompanyOpTicket> cancel(@PathVariable Long id, HttpServletRequest request) {
        LoginUser user = requireGlobal();
        return Result.success("已撤回", ticketService.cancel(id, user.getUsername(), clientIp(request)));
    }

    /** 领取接入凭证回执（仅发起人本人、仅已执行、一次性；密钥不落库） */
    @GetMapping("/{id}/receipt")
    public Result<CompanyOpReceiptVO> receipt(@PathVariable Long id, HttpServletRequest request) {
        LoginUser user = requireGlobal();
        return Result.success("回执已领取（密钥仅展示一次）",
                ticketService.takeReceipt(id, user.getUsername(), clientIp(request)));
    }

    /** 公司生命周期属平台级操作：仅全局账号可参与 */
    private LoginUser requireGlobal() {
        LoginUser user = UserUtils.requireLoginUser();
        if (user.getCompanyUscc() != null) {
            throw new SecurityException("权限不足：公司变更仅限全局账号");
        }
        return user;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
