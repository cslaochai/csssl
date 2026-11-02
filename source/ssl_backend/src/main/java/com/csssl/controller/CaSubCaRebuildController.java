package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.SubCaRebuildApplyDTO;
import com.csssl.dto.SubCaRebuildDecisionDTO;
import com.csssl.dto.SubCaRebuildExecuteDTO;
import com.csssl.service.AuditService;
import com.csssl.service.CaSubCaRebuildService;
import com.csssl.util.CompanyScope;
import com.csssl.util.UserUtils;
import com.csssl.vo.CaSubCaTicketVO;
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
 * 二级CA 重建申请单接口：发起（ca:subca:rebuild:apply）→ 5 名密钥保管人审批（3-of-5，仅保管人可投）→
 * 批准（APPROVED）→ 执行（ca:subca:rebuild:execute，粘贴 ≥3 份额加载根私钥后重建）。
 *
 * <p>审批动作由"是否为该公司保管人"收口，不依赖额外权限点；明细/待办对任意登录人开放（数据域按公司隔离）。</p>
 */
@RestController
@RequestMapping("/admin/ca/subca-rebuild")
@RequiredArgsConstructor
public class CaSubCaRebuildController {

    private final CaSubCaRebuildService rebuildService;
    private final AuditService auditService;

    /** 发起二级CA 重建申请（路由至该公司 5 名密钥保管人审批） */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:apply')")
    @PostMapping("/apply")
    public Result<CaSubCaTicketVO> apply(@Valid @RequestBody SubCaRebuildApplyDTO body, HttpServletRequest request) {
        String company = CompanyScope.requireExplicitCompany(UserUtils.requireLoginUser().getCompanyUscc(), body.getCompanyUscc());
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("已发起二级CA重建申请，已通知 5 名密钥保管人审批",
                toVo(rebuildService.apply(body, username, UserUtils.requireLoginUser().getRealName(), clientIp(request)), username));
    }

    /** 申请单分页查询（status/keyword 过滤；ca:subca:rebuild:apply 可见全部） */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:apply')")
    @GetMapping("/page")
    public Result<Page<CaSubCaTicketVO>> page(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long pageNo,
                                              @RequestParam(defaultValue = "10") long pageSize) {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("查询成功", rebuildService.page(pageNo, pageSize, status, keyword, false, username));
    }

    /** 当前登录人作为保管人且尚未投票的待审单（轻量轮询入口，任意登录人可见自身待办） */
    @GetMapping("/pending")
    public Result<java.util.List<CaSubCaTicketVO>> pending() {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("查询成功", rebuildService.pendingForMe(username));
    }

    /** 申请单详情 */
    @GetMapping("/{id}")
    public Result<CaSubCaTicketVO> detail(@PathVariable Long id) {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("查询成功", rebuildService.detail(id, username));
    }

    /** 审批同意（须为该公司保管人；3-of-5 任意 3 人同意即批准，任一驳回即否决） */
    @PostMapping("/{id}/approve")
    public Result<CaSubCaTicketVO> approve(@PathVariable Long id, @Valid @RequestBody SubCaRebuildDecisionDTO body,
                                           HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("已同意", rebuildService.approve(id, body, username,
                UserUtils.requireLoginUser().getRealName(), clientIp(request)));
    }

    /** 审批驳回（须为该公司保管人；意见必填） */
    @PostMapping("/{id}/reject")
    public Result<CaSubCaTicketVO> reject(@PathVariable Long id, @Valid @RequestBody SubCaRebuildDecisionDTO body,
                                          HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("已驳回", rebuildService.reject(id, body, username,
                UserUtils.requireLoginUser().getRealName(), clientIp(request)));
    }

    /** 撤回申请（仅发起人本人，且仅 PENDING） */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:apply')")
    @PostMapping("/{id}/cancel")
    public Result<CaSubCaTicketVO> cancel(@PathVariable Long id, HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("已撤回", rebuildService.cancel(id, username, clientIp(request)));
    }

    /** 执行重建（须 APPROVED；粘贴 ≥3 份额加载根私钥后重建二级CA） */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:execute')")
    @PostMapping("/{id}/execute")
    public Result<CaSubCaTicketVO> execute(@PathVariable Long id, @Valid @RequestBody SubCaRebuildExecuteDTO body,
                                           HttpServletRequest request) {
        String username = UserUtils.requireLoginUser().getUsername();
        return Result.success("已执行二级CA重建（用根重新签发）", rebuildService.execute(id, body, username,
                UserUtils.requireLoginUser().getRealName(), clientIp(request)));
    }

    private CaSubCaTicketVO toVo(CaSubCaTicketVO vo, String username) {
        return vo;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
