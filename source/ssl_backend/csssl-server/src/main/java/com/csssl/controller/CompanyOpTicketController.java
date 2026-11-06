package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CompanyOpPageQueryDTO;
import com.csssl.dto.IdCommentDTO;
import com.csssl.dto.CompanyOpSubmitDTO;
import com.csssl.dto.CompanyOpVoteDTO;
import com.csssl.dto.IdDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.CompanyOpTicket;
import com.csssl.entity.LoginUser;
import com.csssl.service.CompanyOpTicketService;
import com.csssl.util.UserUtils;
import com.csssl.vo.CompanyOpReceiptVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

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

    /**
     * 变更单分页查询（全局账号；只读，系统管理员可巡检；重新指派保管人单附当前人投票状态）
     */
    @PostMapping("/page")
    public Result<Page<CompanyOpTicket>> page(@Valid @RequestBody CompanyOpPageQueryDTO query) {
        LoginUser user = requireGlobal();
        return Result.success("查询成功", ticketService.page(query.getPageNo(), query.getPageSize(),
                query.getStatus(), query.getOpType(), query.getKeyword(), user));
    }

    /**
     * 变更单详情（附投票明细与当前人投票状态）
     */
    @PostMapping("/detail")
    public Result<CompanyOpTicket> detail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        return Result.success("查询成功", ticketService.detail(body.getId(), user));
    }

    /**
     * 发起变更单（不执行，仅落待审批）
     */
    @PreAuthorize("hasAuthority('company:op:apply')")
    @PostMapping("/submit")
    public Result<CompanyOpTicket> submit(@Valid @RequestBody CompanyOpSubmitDTO body, HttpServletRequest request) {
        LoginUser user = requireGlobal();
        return Result.success("变更单已提交，待审批", ticketService.submit(body,
                user, clientIp(request)));
    }

    /**
     * 审批批准（不得与发起人同一人；批准即执行：状态流转/密钥轮换）
     */
    @PreAuthorize("hasAuthority('company:op:approve')")
    @PostMapping("/approve")
    public Result<CompanyOpTicket> approve(@RequestBody IdCommentDTO body,
                                           HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        return Result.success("已批准并执行", ticketService.approve(body.getId(), body.getComment(), user, clientIp(request)));
    }

    /**
     * 审批驳回（不得与发起人同一人；意见必填；公司状态不变）
     */
    @PreAuthorize("hasAuthority('company:op:approve')")
    @PostMapping("/reject")
    public Result<CompanyOpTicket> reject(@RequestBody IdCommentDTO body,
                                          HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        return Result.success("已驳回", ticketService.reject(body.getId(), body.getComment(), user, clientIp(request)));
    }

    /**
     * 撤回变更单（仅发起人本人；仅待审批可撤）
     */
    @PostMapping("/cancel")
    public Result<CompanyOpTicket> cancel(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        return Result.success("已撤回", ticketService.cancel(body.getId(), user, clientIp(request)));
    }

    /**
     * 原保管人投票（重新指派保管人专用，不经过公司审批员）：
     * 投票人=提交时锁定的原 5 名密钥保管人，每人一票；同意数 >3（≥4）自动执行，驳回 ≥2 即否决。
     * 不做权限点限制：由服务层校验投票人须在变更单 voter_usernames 快照内（密钥管理员持
     * menu:company-ops 进入本页后即可投票）。
     */
    @PostMapping("/vote")
    public Result<CompanyOpTicket> vote(@Valid @RequestBody CompanyOpVoteDTO body,
                                        HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        return Result.success("投票已记录", ticketService.vote(body.getId(), body.getAgree(), body.getComment(),
                user, clientIp(request)));
    }

    /**
     * 领取接入凭证回执（仅发起人本人、仅已执行、一次性；密钥不落库）
     */
    @PostMapping("/receipt")
    public Result<CompanyOpReceiptVO> receipt(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        return Result.success("回执已领取（密钥仅展示一次）",
                ticketService.takeReceipt(body.getId(), user, clientIp(request)));
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
     * 催办：向被指派审批人（或重新指派保管人的原 5 名保管人）推送站内信并补 URGE 轨迹（仅提交人可催办）
     */
    @PreAuthorize("hasAuthority('company:op:apply')")
    @PostMapping("/urge")
    public Result<Void> urge(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobal();
        ticketService.urge(body.getId(), user, clientIp(request));
        return Result.success("已催办", null);
    }

    /**
     * 公司生命周期属平台级操作：仅全局账号可参与
     */
    private LoginUser requireGlobal() {
        // 单租户化后账号不再携带公司域，持权管理员均视为全局管理员
        return UserUtils.requireLoginUser();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
