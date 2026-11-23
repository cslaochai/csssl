package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.CompanyOpSubmitDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CompanyKeyShare;
import com.csssl.entity.CompanyOpTicket;
import com.csssl.entity.CompanyOpVote;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.enums.CompanyOpTypeEnum;
import com.csssl.enums.CompanyStatusEnum;
import com.csssl.enums.TicketStatusEnum;
import com.csssl.mapper.CompanyOpTicketMapper;
import com.csssl.mapper.CompanyOpVoteMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.ApplicationTrailService;
import com.csssl.service.MessageService;
import com.csssl.util.PermissionCatalog;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.CompanyOpReceiptVO;
import com.csssl.workflow.WorkflowEngineService;
import com.csssl.workflow.dto.TaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 公司生命周期变更审批单（暂停/恢复/关闭/重置接入密钥走单）：
 * 发起（持 company:op:apply）→ 审批（持 company:op:approve，GM/T 0034 §10.1g 不得与发起人同一人）
 * → 批准即执行（同事务完成状态流转/密钥轮换 + 单据落终态）。
 * 状态机：PENDING → EXECUTED / REJECTED / CANCELLED（见 {@link TicketStatusEnum}）。
 * 新建公司不属敏感变更，已改为直接执行（见 {@link CompanyProvisionService} / POST /admin/companies）；
 * 本服务不再接受 CREATE 新单，历史 CREATE 单据仍可查询、可领取已发放的凭证回执。
 * 接入密钥明文不落库：单据只记 App ID 与版本，由发起人凭一次性回执从密钥保险库领取。
 *
 * <p>归属到人（工作流引擎统一口径，与 RA 申请 / 证书操作单一致）：除「重新指派保管人」投票分支外，
 * 提交时按「同角色（COMPANY_APPROVER）+ 待审积压最少」自动分派具体审批人，仅被指派（且仍在岗）的审批人可审，
 * 指派人停用/未指派则回落审批员池共同处理；提交/批准/驳回/撤销均留审批轨迹并推送站内信；
 * 待审批变更单可由提交人催办。流程图由 /admin/workflows/diagram 按定义统一驱动。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyOpTicketService {

    private static final DateTimeFormatter OP_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 工作流定义编码：提交即起流程，审批人由流程节点配置决定 */
    private static final String FLOW_CODE = "companyOpTicketFlow";

    /** 重新指派保管人：新保管人数（一人一份，固定 5 人） */
    private static final int HOLDERS_TOTAL = 5;
    /** 执行所需同意数：5 名原保管人中须超过 3 人同意（即至少 4 人） */
    private static final int REASSIGN_AGREE_REQUIRED = 4;
    /** 否决所需驳回数：5-4+1=2，驳回 ≥2 时已无法凑齐 4 同意 */
    private static final int REASSIGN_REJECT_DEAD = 2;

    /**
     * 未指派 / 指派人已停用：回落审批员池共同处理（与列表可见范围、checkAssignee 同一口径）
     */
    private static final String UNCLAIMED_ASSIGNEE_SQL =
            "(assignee_user_id is null or assignee_user_id not in"
                    + " (select id from ssl_sys_user where status = 1))";

    private final CompanyOpTicketMapper ticketMapper;
    private final CompanyOpVoteMapper voteMapper;
    private final CompanyService companyService;
    private final ApiClientService apiClientService;
    private final CaService caService;
    private final AuditService auditService;
    private final SysUserMapper userMapper;
    private final ApplicationTrailService trailService;
    private final MessageService messageService;
    private final WorkflowEngineService engine;

    /**
     * 分页查询（status/opType 精确、keyword 命中 单号/信用代码/公司名称）；
     * 返回前按当前登录人回算两类标志：投票状态（myVotable/myVoted，「重新指派保管人」单）
     * 与本人可做的动作（撤回 / 领取回执 / 审批 / 催办）。
     */
    public Page<CompanyOpTicket> page(long pageNo, long pageSize, String status, String opType, String keyword, LoginUser viewer) {
        LambdaQueryWrapper<CompanyOpTicket> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CompanyOpTicket::getStatus, status.trim());
        }
        if (opType != null && !opType.isBlank()) {
            qw.eq(CompanyOpTicket::getOpType, opType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(CompanyOpTicket::getOpNo, kw).or()
                    .like(CompanyOpTicket::getCompanyUscc, kw).or()
                    .like(CompanyOpTicket::getCompanyName, kw));
        }
        Set<String> myFlowNos = myFlowBizNos(viewer);
        applyViewerScope(qw, viewer, myFlowNos);
        qw.orderByDesc(CompanyOpTicket::getCreatedAt);
        Page<CompanyOpTicket> page = ticketMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        for (CompanyOpTicket t : page.getRecords()) {
            fillVoteState(t, viewer);
            fillActorState(t, viewer);
            markReviewable(t, viewer, myFlowNos);
        }
        return page;
    }

    public CompanyOpTicket detail(Long id, LoginUser viewer) {
        CompanyOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("变更单不存在（id=" + id + "）");
        }
        Set<String> myFlowNos = myFlowBizNos(viewer);
        markReviewable(ticket, viewer, myFlowNos);
        fillVoteState(ticket, viewer);
        fillActorState(ticket, viewer);
        return ticket;
    }

    /**
     * 流程当前环节派给「我」的单号集合：一次取回，供列表可见范围与逐行动作位共用
     * （逐行 engine.canHandle 会按页行数放大成 N+1）。无审批权限点的视角返回空集。
     */
    private Set<String> myFlowBizNos(LoginUser viewer) {
        if (!viewer.getPermissions().contains(PermissionCatalog.COMPANY_OP_APPROVE)) {
            return Set.of();
        }
        Set<String> nos = new HashSet<>();
        for (TaskVO task : engine.myPendingTasks()) {
            if (task.getBusinessId() != null && !task.getBusinessId().isBlank()) {
                nos.add(task.getBusinessId());
            }
        }
        return nos;
    }

    /**
     * 角色可见范围（仅全局账号可参与公司生命周期）：
     * 持 company:op:approve 的公司审批员收窄到「发起人=我 OR 指派处理人=我 OR 审核人=我
     * OR 流程当前环节办理人=我 OR 无人认领的待审批单」；
     * 密钥管理员（KEY_ADMIN）需对「重新指派保管人」单投票，保持全量可见；
     * 监管/只读角色（ADMIN/RA_ADMIN/AUDITOR/SECURITY_ADMIN）全量只读；
     * 普通发起人（COMPANY_ADMIN）仅看自己提交的。
     */
    private void applyViewerScope(LambdaQueryWrapper<CompanyOpTicket> qw, LoginUser viewer, Set<String> myFlowNos) {
        List<String> perms = viewer.getPermissions();
        String role = viewer.getRoleCode();
        boolean canApprove = perms.contains(PermissionCatalog.COMPANY_OP_APPROVE);
        if (Set.of("ADMIN", "RA_ADMIN", "AUDITOR", "SECURITY_ADMIN", "KEY_ADMIN").contains(role)) {
            return;
        }
        if (!canApprove) {
            // 提交人（COMPANY_ADMIN）仅看自己提交的
            qw.eq(CompanyOpTicket::getRequestedByUserId, viewer.getUserId());
            return;
        }
        Long me = viewer.getUserId();
        qw.and(w -> {
            w.eq(CompanyOpTicket::getAssigneeUserId, me)
                    .or().eq(CompanyOpTicket::getRequestedByUserId, me)
                    .or().eq(CompanyOpTicket::getReviewedByUserId, me);
            if (!myFlowNos.isEmpty()) {
                // 当前环节办理人以流程为准（assignee 列只是提交时的快照，节点流转后不再代表处理人）
                w.or().in(CompanyOpTicket::getOpNo, myFlowNos);
            }
            // 「无人认领」逃生门：与 markReviewable 的接管判据同口径，防单据因处理人停用/离职永久卡死；
            // 「重新指派保管人」仍走原保管人投票通道，不在此放开
            w.or(x -> x.eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                    .ne(CompanyOpTicket::getOpType, CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode())
                    .apply(UNCLAIMED_ASSIGNEE_SQL));
        });
    }

    /**
     * 逐行标注当前登录人可做的动作：reviewable（可审批）/ urgeable（可催办）。
     * 「重新指派保管人」单走原保管人投票分支，不进入公司审批员通道，故 reviewable/urgeable 置否。
     * reviewable 与 {@link #checkAssignee} 同一口径，前端据此隐藏按钮（不置灰）：
     * 有流程实例只认「流程当前环节办理人=我」（与列表可见范围同一个集合），历史无实例的单回落到被指派人。
     */
    private void markReviewable(CompanyOpTicket ticket, LoginUser viewer, Set<String> myFlowNos) {
        boolean canReview = viewer.getPermissions().contains(PermissionCatalog.COMPANY_OP_APPROVE);
        if (CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode().equals(ticket.getOpType())) {
            ticket.setReviewable(false);
            ticket.setUrgeable(false);
            return;
        }
        Long me = viewer.getUserId();
        Set<Long> enabled = canReview ? enabledUserIds() : Set.of();
        boolean pending = TicketStatusEnum.PENDING.is(ticket.getStatus());
        boolean flowActive = ticket.getOpNo() != null && engine.hasInstance(ticket.getOpNo());
        ticket.setReviewable(canReview && pending
                && (flowActive
                        ? myFlowNos.contains(ticket.getOpNo())
                        : isAssigneeTakable(ticket.getAssigneeUserId(), me, enabled)));
        ticket.setUrgeable(pending && ticket.getAssigneeUserId() != null);
    }

    /**
     * 指派归属判定（按 user_id 比较）：未指派或指派人已停用/不存在=可接管；已指派且在岗=仅被指派人可审
     */
    private boolean isAssigneeTakable(Long assigneeUserId, Long viewerUserId, Set<Long> enabled) {
        if (assigneeUserId == null) {
            return true;
        }
        if (assigneeUserId.equals(viewerUserId)) {
            return true;
        }
        return !enabled.contains(assigneeUserId);
    }

    /** 全部启用状态账号 id（口径与 {@link #checkAssignee}、列表的「无人认领」SQL 一致：在岗指派人只有本人能办） */
    private Set<Long> enabledUserIds() {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .eq(SysUser::getStatus, 1));
        Set<Long> ids = new HashSet<>();
        users.forEach(u -> ids.add(u.getId()));
        return ids;
    }

    /**
     * 发起变更单（不执行，仅落 PENDING 待审批）。提交时先做一次参数预检（快速失败），执行时由 CompanyService/ApiClientService 再验一次（以执行时为准）。
     * 除「重新指派保管人」外，提交时按角色（COMPANY_APPROVER）+ 待审积压最少自动分派具体审批人。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyOpTicket submit(CompanyOpSubmitDTO dto, LoginUser loginUser, String ip) {
        CompanyOpTypeEnum opType = CompanyOpTypeEnum.fromCode(dto.getOpType());
        if (opType == CompanyOpTypeEnum.CREATE) {
            throw new IllegalArgumentException("新建公司已改为直接执行（无需审批），请使用「新建公司」入口");
        }
        if (opType == null || !opType.isSubmittable()) {
            throw new IllegalArgumentException("变更类型仅支持 SUSPEND-暂停 / RESUME-恢复 / CLOSE-关闭 / RESET_CREDENTIAL-重置接入密钥 / REASSIGN_HOLDERS-重新指派保管人");
        }
        String reason = blankToNull(dto.getReason());
        if (reason == null) {
            throw new IllegalArgumentException("变更事由不能为空（审批依据）");
        }
        String uscc = CompanyService.normalizeUscc(dto.getUscc());
        CaCompany company = companyService.get(uscc);
        checkCompanyStatusForOp(company.getStatus(), opType);
        Long pending = ticketMapper.selectCount(new LambdaQueryWrapper<CompanyOpTicket>()
                .eq(CompanyOpTicket::getCompanyUscc, uscc)
                .eq(CompanyOpTicket::getOpType, opType.getCode())
                .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode()));
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException("该公司已有待审批的同类变更单（" + opType.getLabel() + "），请等待审批完成或先撤回");
        }

        Long actorUserId = loginUser == null ? null : loginUser.getUserId();
        String actorRealName = loginUser == null ? null : loginUser.getRealName();

        CompanyOpTicket ticket = new CompanyOpTicket();
        ticket.setOpNo("TO" + OP_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        ticket.setOpType(opType.getCode());
        ticket.setCompanyUscc(uscc);
        ticket.setCompanyName(company.getCompanyName());
        ticket.setReason(reason);
        ticket.setStatus(TicketStatusEnum.PENDING.getCode());
        ticket.setRequestedByUserId(actorUserId);
        ticket.setRequestedByName(actorRealName);
        ticket.setRequestedAt(LocalDateTime.now());
        ticket.setCreatedAt(LocalDateTime.now());
        if (opType == CompanyOpTypeEnum.REASSIGN_HOLDERS) {
            fillReassignHolders(ticket, dto.getNewHolders(), loginUser);
        }
        ticketMapper.insert(ticket);
        List<SysUser> assignees = List.of();
        if (opType != CompanyOpTypeEnum.REASSIGN_HOLDERS) {
            // 审批人不再由业务侧自动分派：提交即起流程，当前环节办理人由流程节点配置决定
            engine.startForBusiness(FLOW_CODE, ticket.getOpNo(), dto.getAssigneeUserId());
            assignees = engine.pendingAssignees(ticket.getOpNo());
            if (!assignees.isEmpty()) {
                ticket.setAssigneeUserId(assignees.get(0).getId());
                ticket.setAssigneeRealName(assignees.get(0).getRealName());
                ticketMapper.updateById(ticket);
            }
        }

        auditService.record(loginUser, "COMPANY_OP_SUBMIT", ticket.getOpNo(),
                "发起公司变更单：" + opType.getLabel() + " " + targetText(ticket) + "（事由：" + reason + "）",
                ip, uscc);
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "SUBMIT",
                loginUser, null, ticket.getStatus(), null);
        if (opType != CompanyOpTypeEnum.REASSIGN_HOLDERS) {
            if (!assignees.isEmpty()) {
                trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "ASSIGN",
                        loginUser, null, ticket.getStatus(),
                        "流程节点办理人：" + assignees.stream().map(SysUser::getRealName).distinct()
                                .collect(java.util.stream.Collectors.joining("、")));
            }
            for (SysUser assignee : assignees) {
                messageService.send(ticket.getCompanyUscc(), assignee.getId(), assignee.getRealName(),
                        loginUser, "COMPANY_OP_SUBMITTED", "您有一条新的公司变更单待审批",
                        "变更单 " + ticket.getOpNo() + " 待您审批（提交人：" + ticket.getRequestedByName() + "）",
                        "COMPANY_OP", String.valueOf(ticket.getId()));
            }
        }
        log.info("[公司变更单][{}] 发起 {} 目标={} 发起人={} 指派审批人={}", ticket.getOpNo(), opType.getCode(),
                uscc, actorRealName, ticket.getAssigneeRealName());
        return ticket;
    }

    /**
     * 自动分派：取启用状态公司审批员（COMPANY_APPROVER）里当前待审积压最少的一个，同积压按账号升序取首个，
     * 保证同一时刻分派结果可复现。排除发起人本人（分权制衡）。
     */
    private SysUser leastPendingApprover(Long excludeUserId) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .eq(SysUser::getRoleCode, "COMPANY_APPROVER")
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getUsername);
        if (excludeUserId != null) {
            qw.ne(SysUser::getId, excludeUserId);
        }
        List<SysUser> pool = userMapper.selectList(qw);
        if (pool.isEmpty()) {
            throw new IllegalStateException("当前无启用状态且非发起人的公司审批员，无法受理公司变更单");
        }
        Map<Long, Long> backlog = new HashMap<>();
        QueryWrapper<CompanyOpTicket> group = new QueryWrapper<>();
        group.select("assignee_user_id as assignee", "count(1) as cnt")
                .eq("status", TicketStatusEnum.PENDING.getCode())
                .in("assignee_user_id", pool.stream().map(SysUser::getId).toList())
                .groupBy("assignee_user_id");
        for (Map<String, Object> row : ticketMapper.selectMaps(group)) {
            Object assignee = row.get("assignee");
            Object cnt = row.get("cnt");
            if (assignee != null && cnt instanceof Number n) {
                backlog.put(Long.valueOf(String.valueOf(assignee)), n.longValue());
            }
        }
        SysUser chosen = pool.get(0);
        long min = Long.MAX_VALUE;
        for (SysUser u : pool) {
            long cnt = backlog.getOrDefault(u.getId(), 0L);
            if (cnt < min) {
                min = cnt;
                chosen = u;
            }
        }
        return chosen;
    }

    /**
     * 审批批准（不得与发起人同一人）。批准即执行：同事务完成变更并落 EXECUTED；
     * 执行前按当前公司状态重校验（发起后状态可能已变化）。「重新指派保管人」不经过公司审批员，此处拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyOpTicket approve(Long id, String comment, LoginUser viewer, String ip) {
        CompanyOpTicket ticket = checkPending(id, viewer);
        CompanyOpTypeEnum opType = CompanyOpTypeEnum.fromCode(ticket.getOpType());
        if (opType == null) {
            throw new IllegalArgumentException("未知变更类型：" + ticket.getOpType());
        }
        checkReviewerDiff(ticket, viewer);
        if (opType == CompanyOpTypeEnum.REASSIGN_HOLDERS) {
            throw new IllegalArgumentException("「重新指派保管人」由原 5 名密钥保管人投票决定（同意数超过 3 即执行），不支持公司审批员批准");
        }
        approveInFlow(ticket, viewer, comment);
        String reviewerName = viewer.getRealName();
        LocalDateTime now = LocalDateTime.now();

        // 先条件占位（PENDING→EXECUTED）再执行：并发双批只有一个能成功，执行异常整事务回滚
        int reserved = ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                .eq(CompanyOpTicket::getId, id)
                .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .set(CompanyOpTicket::getStatus, TicketStatusEnum.EXECUTED.getCode())
                .set(CompanyOpTicket::getReviewedByUserId, viewer.getUserId())
                .set(CompanyOpTicket::getReviewedByName, reviewerName)
                .set(CompanyOpTicket::getReviewedAt, now)
                .set(CompanyOpTicket::getReviewComment, blankToNull(comment))
                .set(CompanyOpTicket::getExecutedAt, now));
        if (reserved == 0) {
            throw new IllegalArgumentException("该变更单已被处理，请刷新后重试");
        }
        ticket.setStatus(TicketStatusEnum.EXECUTED.getCode());
        ticket.setReviewedByUserId(viewer.getUserId());
        ticket.setReviewedByName(reviewerName);
        ticket.setReviewedAt(now);
        ticket.setReviewComment(blankToNull(comment));
        ticket.setExecutedAt(now);

        switch (opType) {
            case CREATE -> throw new IllegalStateException("「新建公司」已改为直接执行，无需审批；历史待审批单请驳回或撤回");
            case SUSPEND -> {
                companyService.suspend(ticket.getCompanyUscc());
                auditService.record(viewer, "COMPANY_SUSPEND", ticket.getCompanyUscc(),
                        "暂停公司 " + ticket.getCompanyName() + "（变更单 " + ticket.getOpNo() + "）", ip, ticket.getCompanyUscc());
            }
            case RESUME -> {
                companyService.resume(ticket.getCompanyUscc());
                auditService.record(viewer, "COMPANY_RESUME", ticket.getCompanyUscc(),
                        "恢复公司 " + ticket.getCompanyName() + "（变更单 " + ticket.getOpNo() + "）", ip, ticket.getCompanyUscc());
            }
            case CLOSE -> {
                companyService.close(ticket.getCompanyUscc());
                auditService.record(viewer, "COMPANY_CLOSE", ticket.getCompanyUscc(),
                        "关闭公司 " + ticket.getCompanyName() + "（变更单 " + ticket.getOpNo() + "）", ip, ticket.getCompanyUscc());
            }
            case RESET_CREDENTIAL -> executeResetCredential(ticket, viewer, ip);
            case REASSIGN_HOLDERS -> throw new IllegalStateException("「重新指派保管人」由原保管人投票决定，不走审批员批准通道");
        }

        ticketMapper.updateById(ticket);
        auditService.record(viewer, "COMPANY_OP_APPROVE", ticket.getOpNo(),
                "批准并执行公司变更单：" + opType.getLabel() + " " + targetText(ticket)
                        + "（发起人 " + ticket.getRequestedByName() + "）", ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "APPROVE",
                viewer, "PENDING", "EXECUTED", blankToNull(comment));
        // 审批即执行的业务动作已完成，流程收尾
        engine.finish(ticket.getOpNo(), viewer, "批准并执行公司变更单：" + opType.getLabel());
        messageService.send(ticket.getCompanyUscc(), ticket.getRequestedByUserId(), ticket.getRequestedByName(),
                viewer, "COMPANY_OP_APPROVED", "公司变更单已批准并执行",
                "您的变更单 " + ticket.getOpNo() + " 已批准并执行（" + opType.getLabel() + "）",
                "COMPANY_OP", String.valueOf(ticket.getId()));
        log.info("[公司变更单][{}] 批准并执行 {} 目标={} 审批人={}", ticket.getOpNo(), opType.getCode(),
                ticket.getCompanyUscc(), reviewerName);
        return ticket;
    }

    /**
     * 审批驳回（不得与发起人同一人；意见必填；公司状态不变）
     */
    public CompanyOpTicket reject(Long id, String comment, LoginUser viewer, String ip) {
        CompanyOpTicket ticket = checkPending(id, viewer);
        CompanyOpTypeEnum opType = CompanyOpTypeEnum.fromCode(ticket.getOpType());
        if (opType == CompanyOpTypeEnum.REASSIGN_HOLDERS) {
            throw new IllegalArgumentException("「重新指派保管人」由原 5 名密钥保管人投票决定（同意数超过 3 即执行），不支持公司审批员驳回；如原保管人不同意，请他们在本页投票驳回");
        }
        checkReviewerDiff(ticket, viewer);
        rejectInFlow(ticket, viewer, comment);
        String remark = blankToNull(comment);
        if (remark == null) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        int updated = ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                .eq(CompanyOpTicket::getId, id)
                .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .set(CompanyOpTicket::getStatus, TicketStatusEnum.REJECTED.getCode())
                .set(CompanyOpTicket::getReviewedByUserId, viewer.getUserId())
                .set(CompanyOpTicket::getReviewedByName, viewer.getRealName())
                .set(CompanyOpTicket::getReviewedAt, LocalDateTime.now())
                .set(CompanyOpTicket::getReviewComment, remark));
        if (updated == 0) {
            throw new IllegalArgumentException("该变更单已被处理，请刷新后重试");
        }
        auditService.record(viewer, "COMPANY_OP_REJECT", ticket.getOpNo(),
                "驳回公司变更单：" + opText(ticket.getOpType()) + " " + targetText(ticket) + "（意见：" + remark + "）",
                ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "REJECT",
                viewer, "PENDING", "REJECTED", remark);
        messageService.send(ticket.getCompanyUscc(), ticket.getRequestedByUserId(), ticket.getRequestedByName(),
                viewer, "COMPANY_OP_REJECTED", "公司变更单被驳回",
                "您的变更单 " + ticket.getOpNo() + " 被驳回（" + opText(ticket.getOpType())
                        + "，意见：" + remark + "）",
                "COMPANY_OP", String.valueOf(ticket.getId()));
        return detail(id, viewer);
    }

    /**
     * 撤回变更单：仅发起人本人，且仅 PENDING 可撤回（审批人可驳回兜底，无需管理员旁路）
     */
    public CompanyOpTicket cancel(Long id, LoginUser viewer, String ip) {
        CompanyOpTicket ticket = checkPending(id, viewer);
        if (!Objects.equals(ticket.getRequestedByUserId(), viewer.getUserId())) {
            throw new SecurityException("仅变更单发起人本人可撤回");
        }
        int updated = ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                .eq(CompanyOpTicket::getId, id)
                .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .set(CompanyOpTicket::getStatus, TicketStatusEnum.CANCELLED.getCode()));
        if (updated == 0) {
            throw new IllegalArgumentException("该变更单已被处理，请刷新后重试");
        }
        engine.withdrawByBusiness(ticket.getOpNo(), viewer);
        auditService.record(viewer, "COMPANY_OP_CANCEL", ticket.getOpNo(),
                "撤回公司变更单：" + opText(ticket.getOpType()) + " " + targetText(ticket), ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "CANCEL",
                viewer, "PENDING", "CANCELLED", null);
        return detail(id, viewer);
    }

    /**
     * 审批轨迹（按步时间正序，面向业务用户）
     */
    public List<ApplicationTrail> listTrail(Long id) {
        return trailService.listByApplication(id);
    }

    /**
     * 催办：向被指派审批人推送站内信并补 URGE 轨迹（提交人本人操作）。
     * 「重新指派保管人」单无单一审批人，按提交人权限（company:op:apply）放行，回落通知原 5 名保管人。
     */
    public void urge(Long id, LoginUser operator, String ip) {
        CompanyOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("变更单不存在（id=" + id + "）");
        }
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的变更单可催办（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        Long recipientUserId = null;
        String recipientRealName = null;
        if (ticket.getAssigneeUserId() != null) {
            recipientUserId = ticket.getAssigneeUserId();
            recipientRealName = ticket.getAssigneeRealName();
        } else if (CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode().equals(ticket.getOpType())) {
            // 无单一审批人：通知原 5 名密钥保管人（每人一条站内信）
            for (Long holderId : splitIds(ticket.getVoterUserIds())) {
                SysUser holder = userMapper.selectById(holderId);
                messageService.send(ticket.getCompanyUscc(), holderId,
                        holder == null ? null : holder.getRealName(),
                        operator, "COMPANY_OP_URGE", "重新指派保管人待投票催办",
                        "变更单 " + ticket.getOpNo() + " 待原保管人投票（提交人：" + ticket.getRequestedByName() + "）",
                        "COMPANY_OP", String.valueOf(ticket.getId()));
            }
            recipientRealName = "原 5 名密钥保管人";
        } else {
            SysUser approver = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRoleCode, "COMPANY_APPROVER").eq(SysUser::getStatus, 1).last("LIMIT 1"));
            if (approver == null) {
                throw new IllegalStateException("当前暂无启用状态的公司审批员，无法催办");
            }
            recipientUserId = approver.getId();
            recipientRealName = approver.getRealName();
        }
        if (recipientUserId != null) {
            messageService.send(ticket.getCompanyUscc(), recipientUserId, recipientRealName,
                    operator, "COMPANY_OP_URGE", "公司变更单待审批催办",
                    "变更单 " + ticket.getOpNo() + " 待审批催办（提交人：" + ticket.getRequestedByName() + "）",
                    "COMPANY_OP", String.valueOf(ticket.getId()));
        }
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "URGE",
                operator, "PENDING", "PENDING", "催办：" + recipientRealName);
        auditService.record(operator, "COMPANY_OP_URGE", ticket.getOpNo(),
                "催办 " + recipientRealName + " 处理变更单", ip, ticket.getCompanyUscc());
    }

    /**
     * 保管人投票（重新指派保管人专用，不经过公司审批员）：
     * 投票人=提交时锁定的原 5 名密钥保管人（voterUsernames 快照），每人一票、不可重复；
     * 同意数达到 voteRequired（>3 即 ≥4）→ 执行重新绑定并置 EXECUTED；
     * 驳回数达到 2（已无法再凑齐 4 同意）→ 置 REJECTED；
     * 发起人不得投票（分权制衡）。并发投票由行锁 + 条件更新保证只执行一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyOpTicket vote(Long id, Boolean agree, String comment, LoginUser viewer, String ip) {
        if (agree == null) {
            throw new IllegalArgumentException("投票决定不能为空");
        }
        CompanyOpTicket ticket = checkPending(id, viewer);
        if (!CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode().equals(ticket.getOpType())) {
            throw new IllegalArgumentException("仅「重新指派保管人」变更单需要原保管人投票（当前类型：" + opText(ticket.getOpType()) + "）");
        }
        if (viewer.getUserId() != null && viewer.getUserId().equals(ticket.getRequestedByUserId())) {
            throw new SecurityException("发起人不得参与投票（分权制衡：发起与保管分离）");
        }
        List<Long> voters = splitIds(ticket.getVoterUserIds());
        if (viewer.getUserId() == null || !voters.contains(viewer.getUserId())) {
            throw new SecurityException("仅该公司原密钥保管人可对「重新指派保管人」投票（当前账号不在保管人名单内）");
        }
        String remark = blankToNull(comment);
        if (!agree && remark == null) {
            throw new IllegalArgumentException("驳回必须填写投票意见");
        }
        LocalDateTime now = LocalDateTime.now();
        CompanyOpVote vote = new CompanyOpVote();
        vote.setTicketId(id);
        vote.setVoterUserId(viewer.getUserId());
        vote.setVoterRealName(viewer.getRealName());
        vote.setDecision(agree ? "AGREE" : "REJECT");
        vote.setComment(remark);
        vote.setVotedAt(now);
        vote.setCreatedAt(now);
        try {
            voteMapper.insert(vote);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("你已对该变更单投过票，不能重复投票");
        }

        ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                .eq(CompanyOpTicket::getId, id)
                .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .setSql(agree ? "agree_count = agree_count + 1" : "reject_count = reject_count + 1"));
        CompanyOpTicket latest = ticketMapper.selectById(id);
        int agreeCount = latest.getAgreeCount() == null ? 0 : latest.getAgreeCount();
        int rejectCount = latest.getRejectCount() == null ? 0 : latest.getRejectCount();
        int required = latest.getVoteRequired() == null ? REASSIGN_AGREE_REQUIRED : latest.getVoteRequired();

        if (agreeCount >= required) {
            int executed = ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                    .eq(CompanyOpTicket::getId, id)
                    .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                    .set(CompanyOpTicket::getStatus, TicketStatusEnum.EXECUTED.getCode())
                    .set(CompanyOpTicket::getExecutedAt, now));
            if (executed > 0) {
                latest.setStatus(TicketStatusEnum.EXECUTED.getCode());
                latest.setExecutedAt(now);
                executeReassignHolders(latest, viewer, ip);
                auditService.record(viewer, "COMPANY_OP_EXECUTE", ticket.getOpNo(),
                        "原保管人投票达标（同意 " + agreeCount + " ≥ " + required + "）执行重新指派保管人：" + targetText(ticket)
                                + "（新保管人 " + latest.getNewHolderNames() + "，发起人 " + ticket.getRequestedByName() + "）",
                        ip, ticket.getCompanyUscc());
                trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "EXECUTE",
                        viewer, "PENDING", "EXECUTED", "原保管人投票达标（同意 " + agreeCount + " ≥ " + required + "）执行重新指派");
                log.info("[公司变更单][{}] 原保管人投票达标执行 REASSIGN_HOLDERS 目标={} 同意={} 新保管人={} 触发票={}", ticket.getOpNo(),
                        ticket.getCompanyUscc(), agreeCount, latest.getNewHolderNames(), viewer.getRealName());
            }
        } else if (rejectCount >= REASSIGN_REJECT_DEAD) {
            ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                    .eq(CompanyOpTicket::getId, id)
                    .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                    .set(CompanyOpTicket::getStatus, TicketStatusEnum.REJECTED.getCode()));
            trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "REJECT",
                    viewer, "PENDING", "REJECTED", "原保管人驳回达 " + rejectCount + " 票，整单否决");
        } else {
            trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "VOTE",
                    viewer, "PENDING", "PENDING", (agree ? "同意" : "驳回") + (remark != null ? "：" + remark : ""));
        }
        return detail(id, viewer);
    }

    /**
     * REASSIGN_HOLDERS 提交参数组装：校验恰好 5 名新保管人（互不相同、不含发起人），
     * 并锁定原保管人名单快照作为投票人（执行前名单变化不影响投票资格判定）。
     */
    private void fillReassignHolders(CompanyOpTicket ticket, List<String> newHolders, LoginUser loginUser) {
        if (newHolders == null || newHolders.size() != HOLDERS_TOTAL) {
            throw new IllegalArgumentException("重新指派保管人须指定恰好 5 名新保管人（一人一份门限份额）");
        }
        Long requesterId = loginUser == null ? null : loginUser.getUserId();
        Set<Long> distinctIds = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String u : newHolders) {
            String account = u == null ? null : u.trim();
            if (account == null || account.isBlank()) {
                throw new IllegalArgumentException("新保管人账号不能为空");
            }
            SysUser holder = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, account).eq(SysUser::getStatus, 1));
            if (holder == null) {
                throw new IllegalArgumentException("新保管人账号不存在或已停用：" + account);
            }
            if (!distinctIds.add(holder.getId())) {
                throw new IllegalArgumentException("新保管人账号不可重复：" + account);
            }
            if (holder.getId().equals(requesterId)) {
                throw new IllegalArgumentException("发起人不能同时作为新保管人（分权制衡：发起与保管分离）");
            }
            names.add(holder.getRealName());
        }
        List<CompanyKeyShare> holders = caService.listKeyShareHolders(ticket.getCompanyUscc());
        if (holders.size() != HOLDERS_TOTAL) {
            throw new IllegalArgumentException("该公司当前未登记完整的 5 名密钥保管人，无法发起重新指派");
        }
        ticket.setVoterUserIds(String.join(",", holders.stream()
                .map(h -> String.valueOf(h.getHolderUserId()))
                .toList()));
        ticket.setVoterNames(String.join(",", holders.stream()
                .map(CompanyKeyShare::getHolderRealName)
                .toList()));
        ticket.setNewHolderUserIds(String.join(",", distinctIds.stream().map(String::valueOf).toList()));
        ticket.setNewHolderNames(String.join(",", names));
        ticket.setAgreeCount(0);
        ticket.setRejectCount(0);
        ticket.setVoteRequired(REASSIGN_AGREE_REQUIRED);
    }

    /**
     * 执行重新绑定：重新校验公司启用状态后，按新保管人名单重绑 5 份门限份额（一人一份）。
     */
    private void executeReassignHolders(CompanyOpTicket ticket, LoginUser actor, String ip) {
        companyService.requireActive(ticket.getCompanyUscc());
        List<String> holders = splitIds(ticket.getNewHolderUserIds()).stream()
                .map(id -> userMapper.selectById(id))
                .filter(Objects::nonNull)
                .map(SysUser::getUsername)
                .toList();
        caService.assignRootShares(ticket.getCompanyUscc(), holders);
        auditService.record(actor, "CA_SHARE_ASSIGN", ticket.getCompanyUscc(),
                "重新指派根私钥 5 份份额保管人（变更单 " + ticket.getOpNo() + "）：" + ticket.getNewHolderNames(),
                ip, ticket.getCompanyUscc());
    }

    /**
     * 填充投票展示字段（当前登录人视角）：votes 明细、myVotable（可投票）、myVoted（已投票）。
     * 仅 REASSIGN_HOLDERS 单填充，其他类型原样返回。
     */
    private CompanyOpTicket fillVoteState(CompanyOpTicket t, LoginUser viewer) {
        if (!CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode().equals(t.getOpType())) {
            return t;
        }
        List<CompanyOpVote> votes = voteMapper.selectList(new LambdaQueryWrapper<CompanyOpVote>()
                .eq(CompanyOpVote::getTicketId, t.getId())
                .orderByAsc(CompanyOpVote::getVotedAt));
        t.setVotes(votes);
        Long me = viewer == null ? null : viewer.getUserId();
        List<Long> voters = splitIds(t.getVoterUserIds());
        boolean pending = TicketStatusEnum.PENDING.is(t.getStatus());
        boolean inVoters = me != null && voters.contains(me);
        boolean voted = me != null && votes.stream().anyMatch(v -> me.equals(v.getVoterUserId()));
        t.setMyVotable(inVoters && pending && !voted && !me.equals(t.getRequestedByUserId()));
        t.setMyVoted(inVoters && voted);
        return t;
    }

    private List<Long> splitIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    /**
     * 领取接入凭证回执（仅发起人本人、仅已执行、仅 RESET_CREDENTIAL 及历史 CREATE 单据、一次性）。
     * 密钥明文实时从保险库读取，不落单据；领取标记与密钥读取同事务，并发双领只有一个成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyOpReceiptVO takeReceipt(Long id, LoginUser viewer, String ip) {
        CompanyOpTicket ticket = detail(id, viewer);
        if (!TicketStatusEnum.EXECUTED.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「已执行」的变更单可领取接入凭证（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        CompanyOpTypeEnum opType = CompanyOpTypeEnum.fromCode(ticket.getOpType());
        if (opType != CompanyOpTypeEnum.CREATE && opType != CompanyOpTypeEnum.RESET_CREDENTIAL) {
            throw new IllegalArgumentException("该类型变更单没有接入凭证回执");
        }
        if (!Objects.equals(ticket.getRequestedByUserId(), viewer.getUserId())) {
            throw new SecurityException("仅变更单发起人本人可领取接入凭证回执");
        }
        if (ticket.getReceiptTakenAt() != null) {
            throw new IllegalArgumentException("回执已领取，密钥不可再次查看；如需更换请重新发起「重置接入密钥」变更单");
        }
        if (ticket.getResultAppId() == null || ticket.getResultAppId().isBlank()) {
            throw new IllegalStateException("该变更单没有可领取的接入凭证，请重新发起变更单");
        }
        int claimed = ticketMapper.update(null, new LambdaUpdateWrapper<CompanyOpTicket>()
                .eq(CompanyOpTicket::getId, id)
                .isNull(CompanyOpTicket::getReceiptTakenAt)
                .set(CompanyOpTicket::getReceiptTakenAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new IllegalArgumentException("回执已领取，密钥不可再次查看");
        }
        String secret = apiClientService.secretOfAppId(ticket.getResultAppId());

        CompanyOpReceiptVO vo = new CompanyOpReceiptVO();
        vo.setOpNo(ticket.getOpNo());
        vo.setOpType(ticket.getOpType());
        vo.setCompanyUscc(ticket.getCompanyUscc());
        vo.setCompanyName(ticket.getCompanyName());
        vo.setAppId(ticket.getResultAppId());
        vo.setSecret(secret);
        vo.setSecretVersion(ticket.getResultSecretVersion());
        auditService.record(viewer, "COMPANY_OP_RECEIPT", ticket.getOpNo(),
                "领取公司接入凭证回执：" + targetText(ticket) + "（appId=" + ticket.getResultAppId() + "，密钥仅展示一次）",
                ip, ticket.getCompanyUscc());
        return vo;
    }

    /**
     * RESET_CREDENTIAL 执行：轮换/新登记默认接入凭证，旧 secret 立即失效（明文仅经发起人一次性回执交付）
     */
    private void executeResetCredential(CompanyOpTicket ticket, LoginUser actor, String ip) {
        companyService.requireActive(ticket.getCompanyUscc());
        ApiClientSecretVO credential = apiClientService.resetForCompany(ticket.getCompanyUscc(), actor, ip);
        ticket.setResultAppId(credential.getAppId());
        ticket.setResultSecretVersion(credential.getSecretVersion());
        auditService.record(actor, "COMPANY_CREDENTIAL_RESET", ticket.getCompanyUscc(),
                "重置公司接入密钥：" + ticket.getCompanyName() + "（appId=" + credential.getAppId()
                        + "，版本 " + credential.getSecretVersion() + "，变更单 " + ticket.getOpNo() + "）",
                ip, ticket.getCompanyUscc());
    }

    /**
     * 变更类型与公司当前状态的匹配校验（发起时校验一次；执行时由 CompanyService 状态机再挡一次）
     */
    private void checkCompanyStatusForOp(String status, CompanyOpTypeEnum opType) {
        switch (opType) {
            case SUSPEND -> {
                if (!CompanyStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的公司可暂停（当前状态：" + statusText(status) + "）");
                }
            }
            case RESUME -> {
                if (!CompanyStatusEnum.SUSPENDED.is(status) && !CompanyStatusEnum.CLOSED.is(status)) {
                    throw new IllegalArgumentException("仅「已暂停/已关闭」状态的公司可恢复（当前状态：" + statusText(status) + "）");
                }
            }
            case CLOSE -> {
                if (CompanyStatusEnum.CLOSED.is(status)) {
                    throw new IllegalArgumentException("公司已关闭，不能重复关闭");
                }
                if (!CompanyStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的公司可关闭，已暂停公司请先恢复");
                }
            }
            case RESET_CREDENTIAL -> {
                if (!CompanyStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的公司可重置接入密钥（当前状态：" + statusText(status) + "）");
                }
            }
            case REASSIGN_HOLDERS -> {
                if (!CompanyStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的公司可重新指派保管人（当前状态：" + statusText(status) + "）");
                }
            }
            case CREATE -> throw new IllegalArgumentException("「新建公司」不属变更单类型");
        }
    }

    /**
     * 发起与审批分离：审批人不得为发起人本人（GM/T 0034 §10.1g 同款硬要求）
     */
    private void checkReviewerDiff(CompanyOpTicket ticket, LoginUser loginUser) {
        Long reviewerId = loginUser == null ? null : loginUser.getUserId();
        if (reviewerId != null && reviewerId.equals(ticket.getRequestedByUserId())) {
            throw new SecurityException("违反分权制衡：审批人不得与变更单发起人为同一人（GM/T 0034 §10.1g）");
        }
    }

    /**
     * 审批准入：流程实例存在时以「流程节点办理人配置」为唯一口径；
     * 历史存量单没有实例，回落到旧的被指派人校验。
     */
    private void approveInFlow(CompanyOpTicket ticket, LoginUser loginUser, String comment) {
        if (engine.hasInstance(ticket.getOpNo())) {
            engine.approveByBusiness(ticket.getOpNo(), loginUser, comment);
        } else {
            checkAssignee(ticket, loginUser);
        }
    }

    private void rejectInFlow(CompanyOpTicket ticket, LoginUser loginUser, String comment) {
        if (engine.hasInstance(ticket.getOpNo())) {
            engine.rejectByBusiness(ticket.getOpNo(), loginUser, comment);
        } else {
            checkAssignee(ticket, loginUser);
        }
    }

    /**
     * 审批准入（历史单兜底）：本单只能由被指派的审批人处理。未指派（历史单）或指派人已停用/已被删时，
     * 回落到审批员池共同处理——与列表可见范围 {@link #applyViewerScope} 同一口径。
     */
    private void checkAssignee(CompanyOpTicket ticket, LoginUser loginUser) {
        Long assignee = ticket.getAssigneeUserId();
        Long me = loginUser == null ? null : loginUser.getUserId();
        if (assignee == null || assignee.equals(me)) {
            return;
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, assignee).eq(SysUser::getStatus, 1));
        if (user == null) {
            return;
        }
        throw new SecurityException("权限不足：本单已指派给 " + ticket.getAssigneeRealName()
                + " 审批，当前登录账号不是被指派的审批人");
    }

    private CompanyOpTicket checkPending(Long id, LoginUser viewer) {
        CompanyOpTicket ticket = detail(id, viewer);
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的变更单可操作（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        return ticket;
    }

    private String targetText(CompanyOpTicket ticket) {
        String name = ticket.getCompanyName() == null ? "" : " " + ticket.getCompanyName();
        return ticket.getCompanyUscc() + name;
    }

    private String opText(String opType) {
        CompanyOpTypeEnum t = CompanyOpTypeEnum.fromCode(opType);
        return t == null ? opType : t.getLabel();
    }

    private String statusText(String status) {
        TicketStatusEnum s = TicketStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 按当前登录人回算「本人可做的动作」：撤回、领取接入凭证回执。
     * 与 {@link #cancel} / {@link #takeReceipt} 同一口径——发起人登录账号不再随出参下发，前端只看这两个标志。
     */
    private CompanyOpTicket fillActorState(CompanyOpTicket ticket, LoginUser viewer) {
        boolean mine = Objects.equals(ticket.getRequestedByUserId(), viewer == null ? null : viewer.getUserId());
        ticket.setRevocable(TicketStatusEnum.PENDING.is(ticket.getStatus()) && mine);
        CompanyOpTypeEnum opType = CompanyOpTypeEnum.fromCode(ticket.getOpType());
        boolean hasReceipt = opType == CompanyOpTypeEnum.CREATE || opType == CompanyOpTypeEnum.RESET_CREDENTIAL;
        ticket.setReceiptClaimable(TicketStatusEnum.EXECUTED.is(ticket.getStatus()) && hasReceipt && mine
                && ticket.getReceiptTakenAt() == null);
        return ticket;
    }

}
