package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.SubCaRebuildApplyDTO;
import com.csssl.dto.SubCaRebuildDecisionDTO;
import com.csssl.dto.SubCaRebuildExecuteDTO;
import com.csssl.dto.SubcaPageQueryDTO;
import com.csssl.dto.IdDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.LoginUser;
import com.csssl.service.AuditService;
import com.csssl.service.CaSubCaRebuildService;
import com.csssl.util.UserUtils;
import com.csssl.vo.CaSubCaTicketVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 二级CA 重建申请单接口：发起（ca:subca:rebuild:apply）→ 5 名密钥保管人审批（3-of-5，仅保管人可投，
 * 同意时交出本人份额）→ 批准（APPROVED）→ 执行（ca:subca:rebuild:execute，用已收集的 ≥3 份额加载根私钥后重建）。
 *
 * <p>发起与执行都归重建的受害方/动因方（公司管理员，平台侧由安全管理员承接）；保管人只投这一票，
 * 不再既审批又执行。审批动作由"是否为该公司保管人"收口，不依赖额外权限点；
 * 列表只读巡检走 ca:subca:rebuild:view，明细/待办对任意登录人开放（数据域按公司隔离）。</p>
 */
@RestController
@RequestMapping("/admin/ca/subca-rebuild")
@RequiredArgsConstructor
public class CaSubCaRebuildController {

    private final CaSubCaRebuildService rebuildService;
    private final AuditService auditService;

    /**
     * 发起二级CA 重建申请（路由至该公司 5 名密钥保管人审批）
     */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:apply')")
    @PostMapping("/apply")
    public Result<CaSubCaTicketVO> apply(@Valid @RequestBody SubCaRebuildApplyDTO body, HttpServletRequest request) {
        String company = body.getCompanyUscc();
        LoginUser actor = UserUtils.requireLoginUser();
        return Result.success("已发起二级CA重建申请，已通知 5 名密钥保管人审批",
                rebuildService.apply(body, actor, clientIp(request)));
    }

    /**
     * 申请单分页查询（status/keyword 过滤；只读巡检看 ca:subca:rebuild:view，发起/执行方自带可见性）
     */
    @PreAuthorize("hasAnyAuthority('ca:subca:rebuild:view','ca:subca:rebuild:apply','ca:subca:rebuild:execute')")
    @PostMapping("/page")
    public Result<Page<CaSubCaTicketVO>> page(@Valid @RequestBody SubcaPageQueryDTO body) {
        LoginUser actor = UserUtils.requireLoginUser();
        // 数据域后端强制：公司用户锁本公司，全局用户不传则跨公司只读
        String scope = body.getCompanyUscc();
        return Result.success("查询成功", rebuildService.page(body.getPageNo(), body.getPageSize(),
                body.getStatus(), body.getKeyword(), scope, false, actor));
    }

    /**
     * 当前登录人作为发起人且已审批通过（待执行）的申请单（用于「消息待办 → 待我执行」）
     */
    @PostMapping("/mine-executable")
    public Result<java.util.List<CaSubCaTicketVO>> myExecutable() {
        return Result.success("查询成功", rebuildService.myExecutable(UserUtils.requireLoginUser()));
    }

    /**
     * 当前登录人作为保管人且尚未投票的待审单（轻量轮询入口，任意登录人可见自身待办）
     */
    @PostMapping("/pending")
    public Result<java.util.List<CaSubCaTicketVO>> pending() {
        return Result.success("查询成功", rebuildService.pendingForMe(UserUtils.requireLoginUser()));
    }

    /**
     * 申请单详情
     */
    @PostMapping("/detail")
    public Result<CaSubCaTicketVO> detail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", rebuildService.detail(body.getId(), UserUtils.requireLoginUser()));
    }

    /**
     * 审批同意（须为该公司保管人；3-of-5 任意 3 人同意即批准，任一驳回即否决）
     */
    @PostMapping("/approve")
    public Result<CaSubCaTicketVO> approve(@Valid @RequestBody SubCaRebuildDecisionDTO body,
                                           HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("已同意", rebuildService.approve(body.getId(), body, UserUtils.requireLoginUser(), clientIp(request)));
    }

    /**
     * 审批驳回（须为该公司保管人；意见必填）
     */
    @PostMapping("/reject")
    public Result<CaSubCaTicketVO> reject(@Valid @RequestBody SubCaRebuildDecisionDTO body,
                                          HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("已驳回", rebuildService.reject(body.getId(), body, UserUtils.requireLoginUser(), clientIp(request)));
    }

    /**
     * 撤回申请（仅发起人本人，且仅 PENDING）
     */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:apply')")
    @PostMapping("/cancel")
    public Result<CaSubCaTicketVO> cancel(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("已撤回", rebuildService.cancel(body.getId(), UserUtils.requireLoginUser(), clientIp(request)));
    }

    /**
     * 执行重建（须 APPROVED；粘贴 ≥3 份额加载根私钥后重建二级CA）
     */
    @PreAuthorize("hasAuthority('ca:subca:rebuild:execute')")
    @PostMapping("/execute")
    public Result<CaSubCaTicketVO> execute(@Valid @RequestBody SubCaRebuildExecuteDTO body,
                                           HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("已执行二级CA重建（用根重新签发）", rebuildService.execute(body.getId(), body, UserUtils.requireLoginUser(), clientIp(request)));
    }

    /**
     * 审批轨迹（按步时间正序，与 RA 申请 / 操作单 / 变更单同一条轨迹表）
     */
    @PostMapping("/trail")
    public Result<java.util.List<ApplicationTrail>> listTrail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", rebuildService.listTrail(body.getId()));
    }

    /**
     * 催办：向尚未投票的保管人推送站内信并补 URGE 轨迹（仅发起人本人）
     */
    @PostMapping("/urge")
    public Result<Void> urge(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        rebuildService.urge(body.getId(), UserUtils.requireLoginUser(), clientIp(request));
        return Result.success("已催办", null);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
