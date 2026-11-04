package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCertOpTicket;
import com.csssl.entity.CaCompany;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.enums.CertOpTypeEnum;
import com.csssl.enums.TicketStatusEnum;
import com.csssl.mapper.CaCertOpTicketMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.ApplicationTrailService;
import com.csssl.service.MessageService;
import com.csssl.util.CompanyScope;
import com.csssl.util.PermissionCatalog;
import com.csssl.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 证书操作审批单（吊销/挂起/解除挂起/更新/重签双人复核）：
 * 发起（持 cert:revoke / cert:suspend / cert:renew / cert:rekey 权限）→ 审批（持 cert:op:approve，§10.1g 不得与发起人同一人）
 * → 批准即执行（同事务：证书状态流转进 CRL/OCSP 或吊销旧证重签新证 + 单据落终态）。
 * 状态机：PENDING → EXECUTED / REJECTED / CANCELLED（见 {@link TicketStatusEnum}）。直接执行通道已拆除，系统管理员同样只能走单。
 *
 * <p>归属到人（工作流引擎统一口径，与 RA 申请一致）：提交时按「同角色（APPROVER）+ 待审积压最少」自动分派到具体审批人，
 * 仅被指派（且仍在岗）的审批人可审，指派人停用/未指派则回落审批员池共同处理；提交/批准/驳回/撤销均留审批轨迹并推送站内信；
 * 待审批操作单可由提交人催办。流程图由 /admin/workflows/diagram 按定义统一驱动。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertOpTicketService {

    private static final DateTimeFormatter OP_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 工作流定义编码：提交即起流程，审批人由流程节点配置决定 */
    private static final String FLOW_CODE = "certOpTicketFlow";

    /**
     * 未指派 / 指派人已停用：回落审批员池共同处理（与列表可见范围、checkAssignee 同一口径）
     */
    private static final String UNCLAIMED_ASSIGNEE_SQL =
            "(assignee_user_id is null or assignee_user_id not in"
                    + " (select id from ssl_sys_user where status = 1))";

    private final CaCertOpTicketMapper ticketMapper;
    private final CertService certService;
    private final AuditService auditService;
    private final CaService caService;
    private final SysUserMapper userMapper;
    private final ApplicationTrailService trailService;
    private final MessageService messageService;
    private final WorkflowEngineService engine;

    /**
     * 分页查询：数据域（companyScope）+ 角色可见范围（applyViewerScope）双重重收口，服务端强制。
     */
    public Page<CaCertOpTicket> page(long pageNo, long pageSize, String status, String opType,
                                     String keyword, String companyScope, LoginUser viewer) {
        LambdaQueryWrapper<CaCertOpTicket> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CaCertOpTicket::getStatus, status);
        }
        if (opType != null && !opType.isBlank()) {
            qw.eq(CaCertOpTicket::getOpType, opType);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CaCertOpTicket::getCertSubjectCn, keyword).or()
                    .like(CaCertOpTicket::getCertSerialHex, keyword).or()
                    .like(CaCertOpTicket::getOpNo, keyword));
        }
        if (companyScope != null && !companyScope.isBlank()) {
            qw.eq(CaCertOpTicket::getCompanyUscc, companyScope);
        }
        applyViewerScope(qw, viewer);
        qw.orderByDesc(CaCertOpTicket::getCreatedAt);
        Page<CaCertOpTicket> page = ticketMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        markReviewable(page.getRecords(), viewer);
        return page;
    }

    /**
     * 详情（公司用户访问他公司单按不存在处理，不泄露存在性；并按当前登录人回算 reviewable/urgeable/revocable）
     */
    public CaCertOpTicket detailVisible(Long id, LoginUser viewer) {
        CaCertOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("操作单不存在（id=" + id + "）");
        }
        markReviewable(List.of(ticket), viewer);
        return ticket;
    }

    /**
     * 角色可见范围：持 cert:op:approve 的审核员收窄到「与我相关 + 我能处理」，
     * 监管/只读角色（ADMIN/RA_ADMIN/AUDITOR/SECURITY_ADMIN）保持全量只读，普通操作员仅看自己提交的。
     */
    private void applyViewerScope(LambdaQueryWrapper<CaCertOpTicket> qw, LoginUser viewer) {
        List<String> perms = viewer.getPermissions();
        boolean canApprove = perms.contains(PermissionCatalog.CERT_OP_APPROVE);
        if (!canApprove) {
            String role = viewer.getRoleCode();
            if (Set.of("ADMIN", "RA_ADMIN", "AUDITOR", "SECURITY_ADMIN").contains(role)) {
                return;
            }
            qw.eq(CaCertOpTicket::getRequestedByUserId, viewer.getUserId());
            return;
        }
        Long me = viewer.getUserId();
        qw.and(w -> w.eq(CaCertOpTicket::getAssigneeUserId, me)
                .or().eq(CaCertOpTicket::getRequestedByUserId, me)
                .or().eq(CaCertOpTicket::getReviewedByUserId, me)
                // 流程驱动后审批人由节点配置决定：待审单对持审批权的账号放开可见，按钮显隐按节点办理人回算
                .or().eq(CaCertOpTicket::getStatus, TicketStatusEnum.PENDING.getCode()));
    }

    /**
     * 逐行标注当前登录人可做的动作：reviewable（可审批）/ urgeable（可催办）/ revocable（可撤回）。
     * reviewable 与 {@link #checkAssignee} 同一口径，前端据此隐藏按钮（不置灰）。
     */
    private void markReviewable(List<CaCertOpTicket> rows, LoginUser viewer) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean canReview = viewer.getPermissions().contains(PermissionCatalog.CERT_OP_APPROVE);
        Long me = viewer.getUserId();
        Set<Long> enabled = canReview ? enabledApproverIds() : Set.of();
        for (CaCertOpTicket row : rows) {
            boolean pending = TicketStatusEnum.PENDING.is(row.getStatus());
            boolean flowActive = row.getOpNo() != null && engine.hasInstance(row.getOpNo());
            row.setReviewable(canReview && pending
                    && (flowActive
                            ? engine.canHandle(row.getOpNo(), viewer)
                            : isAssigneeTakable(row.getAssigneeUserId(), me, enabled)));
            row.setUrgeable(pending && row.getAssigneeUserId() != null);
            row.setRevocable(pending && Objects.equals(row.getRequestedByUserId(), me));
        }
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

    /** 全部启用状态审核员 id（审批人池规模很小，一次取回避免逐行查库） */
    private Set<Long> enabledApproverIds() {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .eq(SysUser::getRoleCode, "APPROVER")
                .eq(SysUser::getStatus, 1));
        Set<Long> ids = new HashSet<>();
        users.forEach(u -> ids.add(u.getId()));
        return ids;
    }

    /**
     * 发起操作单（不执行，仅落 PENDING 待审批）。提交时按角色（APPROVER）+ 待审积压最少自动分派具体审批人。
     * 校验：操作类型合法、目标证书存在且在本公司域、状态与操作类型匹配、无同证书同类型待审批单、
     * 根CA/二级CA不走操作单（平台级处置）、吊销/挂起必填原因、更新/重签必填新有效期。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaCertOpTicket submit(String opTypeCode, Long certId, String reason, Integer validityDays,
                                 Long assigneeUserId, LoginUser loginUser, String ip, String effCompany) {
        CertOpTypeEnum opType = CertOpTypeEnum.fromCode(opTypeCode);
        if (opType == null) {
            throw new IllegalArgumentException("操作类型仅支持 REVOKE-吊销 / SUSPEND-挂起 / UNSUSPEND-解除挂起 / RENEW-证书更新 / REKEY-密钥更新");
        }
        CaCert cert = certService.findById(certId);
        CompanyScope.requireWriteCompany(effCompany, cert.getCompanyUscc());
        requireCompanyMatch(cert.getCompanyUscc(), effCompany);
        if ("ROOT".equals(cert.getCertType())
                || (opType == CertOpTypeEnum.REKEY && "SUB_CA".equals(cert.getCertType()))) {
            throw new IllegalArgumentException("根CA/二级CA不走操作单流程（CA 密钥处置属系统维护，不在本流程内）");
        }
        checkStatusForOp(cert, opType);
        Long pending = ticketMapper.selectCount(new LambdaQueryWrapper<CaCertOpTicket>()
                .eq(CaCertOpTicket::getCertId, certId)
                .eq(CaCertOpTicket::getOpType, opType.getCode())
                .eq(CaCertOpTicket::getStatus, TicketStatusEnum.PENDING.getCode()));
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException("该证书已有待审批的同类操作单，请等待审批完成后再发起");
        }
        boolean needReason = opType == CertOpTypeEnum.REVOKE || opType == CertOpTypeEnum.SUSPEND;
        if (needReason && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("操作原因不能为空（吊销/挂起必填）");
        }
        boolean needDays = opType == CertOpTypeEnum.RENEW || opType == CertOpTypeEnum.REKEY;
        if (needDays && (validityDays == null || validityDays < 1)) {
            throw new IllegalArgumentException("新有效期天数不能为空（更新/重签必填）");
        }
        if (opType == CertOpTypeEnum.REKEY && !caService.issuedKeyExists(companyOf(cert), cert.getSerialHex())) {
            throw new IllegalArgumentException("该证书私钥由申请方自持（CSR/公钥签发），CA 侧无法重签，请申请方重新走 RA 申请");
        }

        CaCertOpTicket ticket = new CaCertOpTicket();
        ticket.setCompanyUscc(CaCompany.LEGACY_NAMESPACE.equals(cert.getCompanyUscc()) ? null : cert.getCompanyUscc());
        ticket.setOpNo("CO" + OP_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        ticket.setOpType(opType.getCode());
        ticket.setCertId(certId);
        ticket.setCertSerialHex(cert.getSerialHex());
        ticket.setCertSubjectCn(cert.getSubjectCn());
        ticket.setReason(needReason ? reason.trim() : null);
        ticket.setValidityDays(needDays ? validityDays : null);
        ticket.setStatus(TicketStatusEnum.PENDING.getCode());
        ticket.setRequestedByUserId(loginUser == null ? null : loginUser.getUserId());
        ticket.setRequestedByName(loginUser == null ? null : loginUser.getRealName());
        ticket.setRequestedAt(LocalDateTime.now());
        ticket.setCreatedAt(LocalDateTime.now());

        // 审批人不再由业务侧自动分派：提交即起流程，当前环节办理人由流程节点配置决定
        ticketMapper.insert(ticket);
        engine.startForBusiness(FLOW_CODE, ticket.getOpNo(), assigneeUserId);
        List<SysUser> assignees = engine.pendingAssignees(ticket.getOpNo());
        if (!assignees.isEmpty()) {
            ticket.setAssigneeUserId(assignees.get(0).getId());
            ticket.setAssigneeRealName(assignees.get(0).getRealName());
            ticketMapper.updateById(ticket);
        }
        auditService.record(loginUser, "CO_SUBMIT", ticket.getOpNo(),
                "发起证书操作单：" + opType.getLabel() + " " + cert.getSubjectCn()
                        + (needDays ? "（新有效期 " + validityDays + " 天）" : needReason ? "（原因：" + reason + "）" : ""),
                ip, companyOf(cert));
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "SUBMIT",
                loginUser, null, ticket.getStatus(), null);
        if (!assignees.isEmpty()) {
            trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "ASSIGN",
                    loginUser, null, ticket.getStatus(),
                    "流程节点办理人：" + assignees.stream().map(SysUser::getRealName).distinct()
                            .collect(java.util.stream.Collectors.joining("、")));
        }
        for (SysUser assignee : assignees) {
            messageService.send(ticket.getCompanyUscc(), assignee.getId(), assignee.getRealName(),
                    loginUser, "CERT_OP_SUBMITTED", "您有一条新的证书操作单待审批",
                    "操作单 " + ticket.getOpNo() + " 待您审批（提交人：" + ticket.getRequestedByName() + "）",
                    "CERT_OP", String.valueOf(ticket.getId()));
        }
        log.info("[证书操作单][{}] 发起 {} id={} cn={} 指派审批人={}", ticket.getOpNo(), opType.getCode(),
                certId, cert.getSubjectCn(), ticket.getAssigneeRealName());
        return ticket;
    }

    /**
     * 自动分派：取启用状态审核员（APPROVER）里当前待审积压最少的一个，同积压按账号升序取首个，
     * 保证同一时刻分派结果可复现。审批人池与公司审批员/RA审批员分离，仅限持 cert:op:approve 者。
     */
    private SysUser leastPendingApprover(Long excludeUserId) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .eq(SysUser::getRoleCode, "APPROVER")
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getUsername);
        if (excludeUserId != null) {
            qw.ne(SysUser::getId, excludeUserId);
        }
        List<SysUser> pool = userMapper.selectList(qw);
        if (pool.isEmpty()) {
            throw new IllegalStateException("当前无启用状态且非发起人的审核员，无法受理证书操作单");
        }
        Map<Long, Long> backlog = new HashMap<>();
        QueryWrapper<CaCertOpTicket> group = new QueryWrapper<>();
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
     * 审批批准（不得与发起人同一人，GM/T 0034 §10.1g；且须为本单被指派的审批人）。
     * 批准即执行：同事务内完成证书状态流转 + CRL/OCSP 生效 + 单据落 EXECUTED，
     * 执行前重验证书当前状态（发起后状态可能已变化）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaCertOpTicket approve(Long id, String comment, LoginUser loginUser, String ip, String effCompany) {
        CaCertOpTicket ticket = checkPending(id);
        requireCompanyMatch(ticket.getCompanyUscc(), effCompany);
        checkReviewerDiff(ticket, loginUser);
        approveInFlow(ticket, loginUser, comment);
        CertOpTypeEnum opType = CertOpTypeEnum.fromCode(ticket.getOpType());
        if (opType == null) {
            throw new IllegalArgumentException("未知操作类型：" + ticket.getOpType());
        }
        CaCert cert = certService.findById(ticket.getCertId());
        checkStatusForOp(cert, opType);

        switch (opType) {
            case REVOKE -> certService.revoke(cert.getId(), ticket.getReason(), loginUser, ip);
            case SUSPEND -> certService.suspend(cert.getId(), ticket.getReason(), loginUser, ip);
            case UNSUSPEND -> certService.unsuspend(cert.getId(), loginUser, ip);
            case RENEW -> certService.renew(effCompany, cert.getId(), ticket.getValidityDays(), loginUser, ip);
            case REKEY -> certService.rekey(effCompany, cert.getId(), ticket.getValidityDays(), loginUser, ip);
        }

        ticket.setStatus(TicketStatusEnum.EXECUTED.getCode());
        ticket.setReviewedByUserId(loginUser == null ? null : loginUser.getUserId());
        ticket.setReviewedByName(loginUser == null ? null : loginUser.getRealName());
        ticket.setReviewedAt(LocalDateTime.now());
        ticket.setReviewComment(blankToNull(comment));
        ticket.setExecutedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        auditService.record(loginUser, "CO_APPROVE", ticket.getOpNo(),
                "批准并执行证书操作单：" + opType.getLabel() + " " + ticket.getCertSubjectCn()
                        + "（发起人 " + ticket.getRequestedByName() + "）", ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "APPROVE",
                loginUser, "PENDING", "EXECUTED", blankToNull(comment));
        // 审批即执行的业务动作已完成，流程收尾
        engine.finish(ticket.getOpNo(), loginUser, "批准并执行证书操作单：" + opType.getLabel());
        messageService.send(ticket.getCompanyUscc(), ticket.getRequestedByUserId(), ticket.getRequestedByName(),
                loginUser, "CERT_OP_APPROVED", "证书操作单已批准并执行",
                "您的操作单 " + ticket.getOpNo() + " 已批准并执行（" + opType.getLabel() + "）",
                "CERT_OP", String.valueOf(ticket.getId()));
        log.info("[证书操作单][{}] 批准并执行 {} cn={} 审批人={}", ticket.getOpNo(), ticket.getOpType(),
                ticket.getCertSubjectCn(), loginUser == null ? null : loginUser.getRealName());
        return ticket;
    }

    /**
     * 审批驳回（不得与发起人同一人；意见必填；单据落 REJECTED，证书状态不变）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaCertOpTicket reject(Long id, String comment, LoginUser loginUser, String ip, String effCompany) {
        CaCertOpTicket ticket = checkPending(id);
        requireCompanyMatch(ticket.getCompanyUscc(), effCompany);
        checkReviewerDiff(ticket, loginUser);
        rejectInFlow(ticket, loginUser, comment);
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        ticket.setStatus(TicketStatusEnum.REJECTED.getCode());
        ticket.setReviewedByUserId(loginUser == null ? null : loginUser.getUserId());
        ticket.setReviewedByName(loginUser == null ? null : loginUser.getRealName());
        ticket.setReviewedAt(LocalDateTime.now());
        ticket.setReviewComment(comment.trim());
        ticketMapper.updateById(ticket);
        auditService.record(loginUser, "CO_REJECT", ticket.getOpNo(),
                "驳回证书操作单：" + opText(ticket.getOpType()) + " " + ticket.getCertSubjectCn(), ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "REJECT",
                loginUser, "PENDING", "REJECTED", comment.trim());
        messageService.send(ticket.getCompanyUscc(), ticket.getRequestedByUserId(), ticket.getRequestedByName(),
                loginUser, "CERT_OP_REJECTED", "证书操作单被驳回",
                "您的操作单 " + ticket.getOpNo() + " 被驳回（" + opText(ticket.getOpType())
                        + "，意见：" + comment.trim() + "）",
                "CERT_OP", String.valueOf(ticket.getId()));
        return ticket;
    }

    /**
     * 撤销操作单：仅发起人本人，且仅 PENDING 可撤销（审批人可驳回兜底，无需管理员旁路）
     */
    public CaCertOpTicket cancel(Long id, LoginUser loginUser, String ip, String effCompany) {
        CaCertOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("操作单不存在（id=" + id + "）");
        }
        requireCompanyMatch(ticket.getCompanyUscc(), effCompany);
        if (!Objects.equals(ticket.getRequestedByUserId(), loginUser == null ? null : loginUser.getUserId())) {
            throw new SecurityException("仅操作单发起人本人可撤销");
        }
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的操作单可撤销（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        ticket.setStatus(TicketStatusEnum.CANCELLED.getCode());
        ticketMapper.updateById(ticket);
        engine.withdrawByBusiness(ticket.getOpNo(), loginUser);
        auditService.record(loginUser, "CO_CANCEL", ticket.getOpNo(),
                "撤销证书操作单：" + opText(ticket.getOpType()) + " " + ticket.getCertSubjectCn(), ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "CANCEL",
                loginUser, "PENDING", "CANCELLED", null);
        return ticket;
    }

    /**
     * 审批轨迹（按步时间正序，面向业务用户）
     */
    public List<ApplicationTrail> listTrail(Long id) {
        return trailService.listByApplication(id);
    }

    /**
     * 催办：向被指派审批人推送站内信并补 URGE 轨迹（提交人本人操作；数据域校验防越权）。
     */
    public void urge(Long id, LoginUser operator, String ip) {
        CaCertOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("操作单不存在（id=" + id + "）");
        }
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的操作单可催办（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        Long recipientUserId;
        String recipientRealName;
        if (ticket.getAssigneeUserId() != null) {
            recipientUserId = ticket.getAssigneeUserId();
            recipientRealName = ticket.getAssigneeRealName();
        } else {
            SysUser approver = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRoleCode, "APPROVER").eq(SysUser::getStatus, 1).last("LIMIT 1"));
            if (approver == null) {
                throw new IllegalStateException("当前暂无启用状态的审核员，无法催办");
            }
            recipientUserId = approver.getId();
            recipientRealName = approver.getRealName();
        }
        messageService.send(ticket.getCompanyUscc(), recipientUserId, recipientRealName,
                operator, "CERT_OP_URGE", "证书操作单待审批催办",
                "操作单 " + ticket.getOpNo() + " 待审批催办（提交人：" + ticket.getRequestedByName() + "）",
                "CERT_OP", String.valueOf(ticket.getId()));
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "URGE",
                operator, "PENDING", "PENDING", "催办审批人：" + recipientRealName);
        auditService.record(operator, "CERT_OP_URGE", ticket.getOpNo(),
                "催办审批人 " + recipientRealName + " 处理操作单", ip, ticket.getCompanyUscc());
    }

    /**
     * 审批准入：流程实例存在时以「流程节点办理人配置」为唯一口径；
     * 历史存量单没有实例，回落到旧的被指派人校验。
     */
    private void approveInFlow(CaCertOpTicket ticket, LoginUser loginUser, String comment) {
        if (engine.hasInstance(ticket.getOpNo())) {
            engine.approveByBusiness(ticket.getOpNo(), loginUser, comment);
        } else {
            checkAssignee(ticket, loginUser);
        }
    }

    private void rejectInFlow(CaCertOpTicket ticket, LoginUser loginUser, String comment) {
        if (engine.hasInstance(ticket.getOpNo())) {
            engine.rejectByBusiness(ticket.getOpNo(), loginUser, comment);
        } else {
            checkAssignee(ticket, loginUser);
        }
    }

    /**
     * 审批准入（历史单兜底）：本单只能由被指派的审批人处理。未指派（历史单）或指派人已停用/已被删时，
     * 回落到审核员池共同处理——与列表可见范围 {@link #applyViewerScope} 同一口径。
     */
    private void checkAssignee(CaCertOpTicket ticket, LoginUser loginUser) {
        Long assignee = ticket.getAssigneeUserId();
        if (assignee == null || assignee.equals(loginUser == null ? null : loginUser.getUserId())) {
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

    /**
     * 操作类型与证书当前状态的匹配校验（发起与执行前各验一次）
     */
    private void checkStatusForOp(CaCert cert, CertOpTypeEnum opType) {
        String status = cert.getStatus();
        switch (opType) {
            case REVOKE -> {
                if (!"VALID".equals(status) && !"FROZEN".equals(status)) {
                    throw new IllegalArgumentException("仅「有效/已挂起」状态的证书可发起吊销（当前状态：" + status + "）");
                }
            }
            case SUSPEND -> {
                if (!"VALID".equals(status)) {
                    throw new IllegalArgumentException("仅「有效」状态的证书可发起挂起（当前状态：" + status + "）");
                }
            }
            case UNSUSPEND -> {
                if (!"FROZEN".equals(status)) {
                    throw new IllegalArgumentException("仅「已挂起」状态的证书可发起解除挂起（当前状态：" + status + "）");
                }
            }
            case RENEW, REKEY -> {
                if (!"VALID".equals(status) && !"FROZEN".equals(status)) {
                    throw new IllegalArgumentException("仅「有效/已挂起」状态的证书可发起更新/重签（当前状态：" + status + "）");
                }
            }
        }
    }

    private void requireCompanyMatch(String rowCompany, String effCompany) {
        String expected = rowCompany == null || rowCompany.isBlank() ? "default" : rowCompany;
        if (!expected.equals(effCompany)) {
            throw new SecurityException("权限不足：操作单归属公司 " + expected + "，与当前操作域 " + effCompany + " 不一致");
        }
    }

    /**
     * 发起与审批分离：审批人不得为发起人本人（GM/T 0034 §10.1g 同款硬要求）
     */
    private void checkReviewerDiff(CaCertOpTicket ticket, LoginUser loginUser) {
        Long reviewerId = loginUser == null ? null : loginUser.getUserId();
        if (reviewerId != null && reviewerId.equals(ticket.getRequestedByUserId())) {
            throw new SecurityException("违反分权制衡：审批人不得与操作单发起人为同一人（GM/T 0034 §10.1g）");
        }
    }

    private CaCertOpTicket checkPending(Long id) {
        CaCertOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("操作单不存在（id=" + id + "）");
        }
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的操作单可审批（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        return ticket;
    }

    private String companyOf(CaCert cert) {
        return CompanyScope.companyOfRow(cert.getCompanyUscc());
    }

    private String opText(String opType) {
        CertOpTypeEnum t = CertOpTypeEnum.fromCode(opType);
        return t == null ? opType : t.getLabel();
    }

    private String statusText(String status) {
        TicketStatusEnum s = TicketStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
