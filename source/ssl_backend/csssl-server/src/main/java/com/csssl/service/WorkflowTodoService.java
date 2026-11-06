package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaCertOpTicket;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CaSubCaApproval;
import com.csssl.entity.CaSubCaTicket;
import com.csssl.entity.CertApplication;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.CompanyKeyShare;
import com.csssl.entity.CompanyOpTicket;
import com.csssl.entity.CompanyOpVote;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.enums.CertOpTypeEnum;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.enums.CompanyOpTypeEnum;
import com.csssl.enums.TicketRebuildStatusEnum;
import com.csssl.enums.TicketStatusEnum;
import com.csssl.mapper.CaCertOpTicketMapper;
import com.csssl.mapper.CaSubCaApprovalMapper;
import com.csssl.mapper.CaSubCaTicketMapper;
import com.csssl.mapper.CertApplicationMapper;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.mapper.CompanyKeyShareMapper;
import com.csssl.mapper.CompanyMapper;
import com.csssl.mapper.CompanyOpTicketMapper;
import com.csssl.mapper.CompanyOpVoteMapper;
import com.csssl.mapper.FlowInstanceMapper;
import com.csssl.mapper.FlowTaskMapper;
import com.csssl.mapper.FlowUserMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.util.PermissionCatalog;
import com.csssl.vo.CaSubCaTicketVO;
import com.csssl.vo.WorkflowFlowHistoryVO;
import com.csssl.vo.WorkflowTodoBoardVO;
import com.csssl.vo.WorkflowTodoDetailVO;
import com.csssl.vo.WorkflowTodoVO;
import com.csssl.workflow.WorkflowDefinition;
import com.csssl.workflow.WorkflowEngineService;
import com.csssl.workflow.WorkflowRegistry;
import com.csssl.workflow.WorkflowStep;
import com.csssl.workflow.dto.TaskVO;
import com.csssl.workflow.model.FlowHisTask;
import com.csssl.workflow.model.FlowInstance;
import com.csssl.workflow.model.FlowTask;
import com.csssl.workflow.model.FlowUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一待办聚合 + 待办详情：把四条审批流（RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建）
 * 收敛成「我的待办」工作台唯一入口——四个业务页降级为只读台账后，审批与执行都发生在这块板上。
 *
 * <p>两条口径必须与写接口完全一致，否则前端要么点了报 403、要么看不到该办的单：</p>
 * <ul>
 *   <li><b>可见范围</b>：发起人 / 指派处理人 / 审核人 / 流程当前环节办理人，外加"待审且无人认领"的
 *       单据；不再对全体审批角色放开全量待审单；</li>
 *   <li><b>可执行动作</b>：{@code actions} 按每类单据写接口的权限点 + 状态 + 身份逐行算出，
 *       空集合即只读，前端不得自己拼权限。</li>
 * </ul>
 *
 * <p>一次请求内流程侧数据只取一次（{@code myPendingTasks()}），其余按单号批量回查实例与办理人；
 * 公司名称、模板编码同样批量 in 补齐——逐行调引擎就是 N+1。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkflowTodoService {

    public static final String BIZ_CERT_APPLICATION = "CERT_APPLICATION";
    public static final String BIZ_CERT_OP = "CERT_OP";
    public static final String BIZ_COMPANY_OP = "COMPANY_OP";
    public static final String BIZ_SUB_CA_REBUILD = "SUB_CA_REBUILD";

    /** 审批通过（当前环节轮到我 + 持该流审批权限点 + 单据待审） */
    public static final String ACTION_APPROVE = "APPROVE";
    /** 驳回 */
    public static final String ACTION_REJECT = "REJECT";
    /** 签发证书（申请单已批准且我是签发环节办理人） */
    public static final String ACTION_ISSUE = "ISSUE";
    /** 投票（SUB_CA 重建的保管人投票 / 重新指派保管人的原 5 名保管人投票） */
    public static final String ACTION_VOTE = "VOTE";
    /** 执行重建（已批准，仅发起人本人） */
    public static final String ACTION_EXECUTE_REBUILD = "EXECUTE_REBUILD";
    /** 领取接入凭证回执（仅发起人本人、一次性） */
    public static final String ACTION_RECEIPT = "RECEIPT";
    /** 撤回（仅发起人本人、单据未终结） */
    public static final String ACTION_CANCEL = "CANCEL";
    /** 催办（球还在别人手上，仅发起人本人） */
    public static final String ACTION_URGE = "URGE";

    private static final String FLOW_TASK_PENDING = "PENDING";

    /** 无人认领判据（与各业务台账的可见范围同一口径）：未指派，或指派账号已停用/不存在——防单据因处理人离职永久卡死 */
    private static final String UNCLAIMED_ASSIGNEE_SQL =
            "(assignee_user_id is null or assignee_user_id not in"
                    + " (select id from ssl_sys_user where status = 1))";

    /** 需要我动手的动作：有任一即落「待我处理」；只剩 CANCEL/URGE 的落「我发起的」 */
    private static final Set<String> SELF_SERVE_ACTIONS = Set.of(ACTION_APPROVE, ACTION_REJECT,
            ACTION_ISSUE, ACTION_VOTE, ACTION_EXECUTE_REBUILD, ACTION_RECEIPT);

    private final CertApplicationMapper applicationMapper;
    private final CaCertOpTicketMapper certOpMapper;
    private final CompanyOpTicketMapper companyOpMapper;
    private final CaSubCaTicketMapper subCaMapper;
    private final CaSubCaApprovalMapper subCaApprovalMapper;
    private final CompanyKeyShareMapper keyShareMapper;
    private final CompanyOpVoteMapper companyOpVoteMapper;
    private final CompanyMapper companyMapper;
    private final CertTemplateMapper certTemplateMapper;
    private final FlowInstanceMapper flowInstanceMapper;
    private final FlowTaskMapper flowTaskMapper;
    private final FlowUserMapper flowUserMapper;
    private final SysUserMapper sysUserMapper;
    private final WorkflowRegistry registry;
    private final WorkflowEngineService engine;
    private final CertApplicationService applicationService;
    private final CertOpTicketService certOpTicketService;
    private final CompanyOpTicketService companyOpTicketService;
    private final CaSubCaRebuildService subCaRebuildService;

    /**
     * 聚合当前登录人的待办看板（待我处理 / 我发起的 / 已办）。
     */
    public WorkflowTodoBoardVO board(LoginUser viewer) {
        Board ctx = new Board(viewer);
        collectApplications(ctx);
        collectCertOps(ctx);
        collectCompanyOps(ctx);
        collectSubCa(ctx);

        List<WorkflowTodoVO> all = new ArrayList<>(ctx.pending);
        all.addAll(ctx.initiated);
        all.addAll(ctx.done);
        enrich(all, ctx);

        Comparator<WorkflowTodoVO> byTime = Comparator.comparing(
                v -> v.getRequestedAt() == null ? "" : v.getRequestedAt(), Comparator.reverseOrder());
        ctx.pending.sort(byTime);
        ctx.initiated.sort(byTime);
        ctx.done.sort(byTime);

        WorkflowTodoBoardVO board = new WorkflowTodoBoardVO();
        board.setPending(ctx.pending);
        board.setInitiated(ctx.initiated);
        board.setDone(ctx.done);
        return board;
    }

    /**
     * 待办详情弹窗载荷：待办行（含 actions）+ 完整业务行 + 审批轨迹 + 流程轨迹。
     *
     * <p>可见性复用看板的建行逻辑：先用同一套规则把这行算出来，算不出来说明它本来就不该
     * 出现在你的待办里，直接拒绝——本接口因此不能被用来按 id 读任意单据。</p>
     */
    public WorkflowTodoDetailVO detailTodo(String bizType, Long bizId, LoginUser viewer) {
        if (bizId == null) {
            throw new IllegalArgumentException("参数 bizId 不能为空");
        }
        Board ctx = new Board(viewer);
        Item item = requireRow(ctx, bizType, bizId);
        WorkflowTodoVO row = item.vo();

        WorkflowTodoDetailVO out = new WorkflowTodoDetailVO();
        out.setBizType(row.getBizType());
        out.setBizTypeName(row.getBizTypeName());
        out.setRow(row);
        switch (row.getBizType()) {
            case BIZ_CERT_APPLICATION -> {
                out.setDetail(applicationService.detailVisible(bizId, viewer));
                out.setTrail(applicationService.listTrail(bizId));
            }
            case BIZ_CERT_OP -> {
                out.setDetail(certOpTicketService.detailVisible(bizId, viewer));
                out.setTrail(certOpTicketService.listTrail(bizId));
            }
            case BIZ_COMPANY_OP -> {
                out.setDetail(companyOpTicketService.detail(bizId, viewer));
                out.setTrail(companyOpTicketService.listTrail(bizId));
            }
            case BIZ_SUB_CA_REBUILD -> {
                CaSubCaTicketVO ticket = subCaRebuildService.detail(bizId, viewer);
                out.setDetail(ticket);
                // 本流是保管人逐人投票、无按步留痕表，投票明细即审批轨迹（VO 不含份额密文）
                out.setTrail(ticket.getApprovals());
            }
            default -> throw new IllegalArgumentException("未知业务类型：" + bizType);
        }
        out.setFlowHistory(flowHistory(row.getBizNo()));
        enrich(List.of(row), ctx);
        return out;
    }

    // ==================== RA 证书申请 ====================

    private void collectApplications(Board ctx) {
        LambdaQueryWrapper<CertApplication> qw = new LambdaQueryWrapper<>();
        qw.and(w -> {
            w.eq(CertApplication::getAppliedByUserId, ctx.me)
                    .or().eq(CertApplication::getAssigneeUserId, ctx.me)
                    .or().eq(CertApplication::getReviewedByUserId, ctx.me);
            if (!ctx.flowMine.isEmpty()) {
                w.or().in(CertApplication::getApplyNo, ctx.flowMine);
            }
            w.or(x -> x.eq(CertApplication::getStatus, CertApplicationStatusEnum.PENDING.getCode())
                    .apply(UNCLAIMED_ASSIGNEE_SQL));
        });
        List<CertApplication> rows = applicationMapper.selectList(qw);
        ctx.loadFlowState(rows.stream().map(CertApplication::getApplyNo).toList());
        for (CertApplication app : rows) {
            add(ctx, appItem(ctx, app));
        }
    }

    private Item appItem(Board ctx, CertApplication app) {
        WorkflowTodoVO vo = base(ctx, BIZ_CERT_APPLICATION, app.getId(), app.getApplyNo(),
                app.getApplicantName(), app.getCompanyUscc(), null,
                app.getStatus(), app.getAppliedByName(), fmt(app.getAppliedAt()));
        vo.setApplicantName(app.getApplicantName());
        vo.setSubjectDn(app.getSubjectDn());
        vo.setExternalChannel(blankToNull(app.getChannel()));
        vo.setAssigneeRealName(ctx.currentHandler(app.getApplyNo(), app.getAssigneeRealName()));
        if (app.getTemplateId() != null) {
            ctx.templateIdByBizId.put(app.getApplyNo(), app.getTemplateId());
        }

        boolean pending = CertApplicationStatusEnum.PENDING.is(app.getStatus());
        boolean approved = CertApplicationStatusEnum.APPROVED.is(app.getStatus());
        boolean mine = Objects.equals(ctx.me, app.getAppliedByUserId());
        boolean mayAct = mayAct(ctx, app.getApplyNo(), app.getAssigneeUserId());
        List<String> actions = vo.getActions();
        if (pending && mayAct) {
            if (ctx.canAppApprove) {
                actions.add(ACTION_APPROVE);
            }
            if (ctx.canAppReject) {
                actions.add(ACTION_REJECT);
            }
        }
        if (approved && mayAct && ctx.canAppIssue) {
            actions.add(ACTION_ISSUE);
        }
        if (mine && (pending || approved) && ctx.canAppCancel) {
            actions.add(ACTION_CANCEL);
        }
        if (mine && pending && app.getAssigneeUserId() != null && !ctx.isHandler(app.getApplyNo())
                && ctx.canAppUrge) {
            actions.add(ACTION_URGE);
        }
        return classify(vo, mine,
                Objects.equals(ctx.me, app.getReviewedByUserId())
                        || Objects.equals(ctx.me, app.getAssigneeUserId()),
                pending || approved);
    }

    // ==================== 证书操作单 ====================

    private void collectCertOps(Board ctx) {
        LambdaQueryWrapper<CaCertOpTicket> qw = new LambdaQueryWrapper<>();
        qw.and(w -> {
            w.eq(CaCertOpTicket::getRequestedByUserId, ctx.me)
                    .or().eq(CaCertOpTicket::getAssigneeUserId, ctx.me)
                    .or().eq(CaCertOpTicket::getReviewedByUserId, ctx.me);
            if (!ctx.flowMine.isEmpty()) {
                w.or().in(CaCertOpTicket::getOpNo, ctx.flowMine);
            }
            w.or(x -> x.eq(CaCertOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                    .apply(UNCLAIMED_ASSIGNEE_SQL));
        });
        List<CaCertOpTicket> rows = certOpMapper.selectList(qw);
        ctx.loadFlowState(rows.stream().map(CaCertOpTicket::getOpNo).toList());
        for (CaCertOpTicket t : rows) {
            add(ctx, certOpItem(ctx, t));
        }
    }

    private Item certOpItem(Board ctx, CaCertOpTicket t) {
        WorkflowTodoVO vo = base(ctx, BIZ_CERT_OP, t.getId(), t.getOpNo(),
                certOpTitle(t), t.getCompanyUscc(), null,
                t.getStatus(), t.getRequestedByName(), fmt(t.getRequestedAt()));
        CertOpTypeEnum type = CertOpTypeEnum.fromCode(t.getOpType());
        vo.setOpType(t.getOpType());
        vo.setOpTypeText(type == null ? t.getOpType() : type.getLabel());
        vo.setCertSubjectCn(t.getCertSubjectCn());
        vo.setCertSerial(t.getCertSerialHex());
        vo.setAssigneeRealName(ctx.currentHandler(t.getOpNo(), t.getAssigneeRealName()));

        boolean pending = TicketStatusEnum.PENDING.is(t.getStatus());
        boolean mine = Objects.equals(ctx.me, t.getRequestedByUserId());
        List<String> actions = vo.getActions();
        // 审批即执行：批准与驳回共用 cert:op:approve（与写接口的 @PreAuthorize 同一口径）
        if (pending && mayAct(ctx, t.getOpNo(), t.getAssigneeUserId()) && ctx.canCertOpApprove) {
            actions.add(ACTION_APPROVE);
            actions.add(ACTION_REJECT);
        }
        if (mine && pending) {
            actions.add(ACTION_CANCEL);
        }
        if (mine && pending && t.getAssigneeUserId() != null && !ctx.isHandler(t.getOpNo()) && ctx.canAppUrge) {
            actions.add(ACTION_URGE);
        }
        return classify(vo, mine,
                Objects.equals(ctx.me, t.getReviewedByUserId())
                        || Objects.equals(ctx.me, t.getAssigneeUserId()),
                pending);
    }

    // ==================== 公司变更单 ====================

    private void collectCompanyOps(Board ctx) {
        LambdaQueryWrapper<CompanyOpTicket> qw = new LambdaQueryWrapper<>();
        qw.and(w -> {
            w.eq(CompanyOpTicket::getRequestedByUserId, ctx.me)
                    .or().eq(CompanyOpTicket::getAssigneeUserId, ctx.me)
                    .or().eq(CompanyOpTicket::getReviewedByUserId, ctx.me);
            if (!ctx.flowMine.isEmpty()) {
                w.or().in(CompanyOpTicket::getOpNo, ctx.flowMine);
            }
            w.or(x -> x.eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                    .ne(CompanyOpTicket::getOpType, CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode())
                    .apply(UNCLAIMED_ASSIGNEE_SQL));
            // 重指定保管人不走流程引擎，办理人是 voter_user_ids 快照里的原 5 名保管人
            w.or(x -> x.eq(CompanyOpTicket::getOpType, CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode())
                    .eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                    .apply("find_in_set({0}, voter_user_ids)", ctx.me));
        });
        List<CompanyOpTicket> rows = companyOpMapper.selectList(qw);
        ctx.loadFlowState(rows.stream().map(CompanyOpTicket::getOpNo).toList());
        for (CompanyOpTicket t : rows) {
            add(ctx, companyOpItem(ctx, t));
        }
    }

    private Item companyOpItem(Board ctx, CompanyOpTicket t) {
        WorkflowTodoVO vo = base(ctx, BIZ_COMPANY_OP, t.getId(), t.getOpNo(),
                companyOpTitle(t), t.getCompanyUscc(), t.getCompanyName(),
                t.getStatus(), t.getRequestedByName(), fmt(t.getRequestedAt()));
        CompanyOpTypeEnum type = CompanyOpTypeEnum.fromCode(t.getOpType());
        vo.setOpType(t.getOpType());
        vo.setOpTypeText(type == null ? t.getOpType() : type.getLabel());
        vo.setAssigneeRealName(ctx.currentHandler(t.getOpNo(), t.getAssigneeRealName()));

        boolean pending = TicketStatusEnum.PENDING.is(t.getStatus());
        boolean mine = Objects.equals(ctx.me, t.getRequestedByUserId());
        boolean reassign = CompanyOpTypeEnum.REASSIGN_HOLDERS.is(t.getOpType());
        boolean iVoter = ctx.me != null && splitIds(t.getVoterUserIds()).contains(ctx.me);
        boolean iVoted = ctx.votedCompanyOps.contains(t.getId());
        List<String> actions = vo.getActions();
        if (reassign) {
            // 口径同 CompanyOpTicketService#fillVoteState 的 myVotable：投票人须非发起人且尚未投票
            if (pending && iVoter && !iVoted && !mine) {
                actions.add(ACTION_VOTE);
            }
        } else if (pending && mayAct(ctx, t.getOpNo(), t.getAssigneeUserId()) && ctx.canCompanyOpApprove) {
            actions.add(ACTION_APPROVE);
            actions.add(ACTION_REJECT);
        }
        if (canTakeReceipt(ctx, t)) {
            actions.add(ACTION_RECEIPT);
        }
        if (mine && pending) {
            actions.add(ACTION_CANCEL);
        }
        if (mine && pending && !reassign && t.getAssigneeUserId() != null
                && !ctx.isHandler(t.getOpNo()) && ctx.canCompanyOpApply) {
            actions.add(ACTION_URGE);
        }
        return classify(vo, mine,
                Objects.equals(ctx.me, t.getReviewedByUserId())
                        || Objects.equals(ctx.me, t.getAssigneeUserId())
                        || (reassign && iVoter),
                pending);
    }

    /** 回执可领取条件逐条对齐 CompanyOpTicketService#takeReceipt，避免按钮点了才报错 */
    private boolean canTakeReceipt(Board ctx, CompanyOpTicket t) {
        CompanyOpTypeEnum type = CompanyOpTypeEnum.fromCode(t.getOpType());
        return TicketStatusEnum.EXECUTED.is(t.getStatus())
                && (type == CompanyOpTypeEnum.CREATE || type == CompanyOpTypeEnum.RESET_CREDENTIAL)
                && Objects.equals(ctx.me, t.getRequestedByUserId())
                && t.getReceiptTakenAt() == null
                && notBlank(t.getResultAppId());
    }

    // ==================== SUB_CA 重建（3-of-5 保管人投票） ====================

    private void collectSubCa(Board ctx) {
        LambdaQueryWrapper<CaSubCaTicket> qw = new LambdaQueryWrapper<>();
        if (ctx.holderCompanies.isEmpty()) {
            qw.eq(CaSubCaTicket::getRequestedByUserId, ctx.me);
        } else {
            qw.and(w -> w.eq(CaSubCaTicket::getRequestedByUserId, ctx.me)
                    .or().in(CaSubCaTicket::getCompanyUscc, ctx.holderCompanies));
        }
        List<CaSubCaTicket> rows = subCaMapper.selectList(qw);
        ctx.loadFlowState(rows.stream().map(CaSubCaTicket::getOpNo).toList());
        for (CaSubCaTicket t : rows) {
            add(ctx, subCaItem(ctx, t));
        }
    }

    private Item subCaItem(Board ctx, CaSubCaTicket t) {
        WorkflowTodoVO vo = base(ctx, BIZ_SUB_CA_REBUILD, t.getId(), t.getOpNo(),
                t.getReason(), t.getCompanyUscc(), t.getCompanyName(),
                t.getStatus(), t.getRequestedByName(), fmt(t.getRequestedAt()));
        vo.setAssigneeRealName(ctx.currentHandler(t.getOpNo(), null));

        boolean pending = TicketRebuildStatusEnum.PENDING.is(t.getStatus());
        boolean approved = TicketRebuildStatusEnum.APPROVED.is(t.getStatus());
        boolean mine = Objects.equals(ctx.me, t.getRequestedByUserId());
        boolean isHolder = ctx.holderCompanies.contains(t.getCompanyUscc());
        boolean voted = ctx.votedSubCaTickets.contains(t.getId());
        List<String> actions = vo.getActions();
        if (pending && isHolder && !voted) {
            actions.add(ACTION_VOTE);
        }
        if (approved && mine && ctx.canSubCaExecute) {
            // 已批准、待发起人提交份额解封并重建
            actions.add(ACTION_EXECUTE_REBUILD);
        }
        if (pending && mine && ctx.canSubCaApply) {
            actions.add(ACTION_CANCEL);
        }
        // 催办写接口无权限点、仅校验发起人本人；已轮到我自己投票时不必催
        if (pending && mine && !actions.contains(ACTION_VOTE)) {
            actions.add(ACTION_URGE);
        }
        return classify(vo, mine, voted, pending || approved);
    }

    // ==================== 公共 ====================

    /**
     * 组装条目骨架：通用列 + 流程实例存在性；当前环节与处理人标签仍按工作流定义推导。
     */
    private WorkflowTodoVO base(Board ctx, String bizType, Long id, String bizNo, String title,
                                String companyUscc, String companyName, String status,
                                String requestedByName, String requestedAt) {
        WorkflowTodoVO vo = new WorkflowTodoVO();
        vo.setBizType(bizType);
        WorkflowDefinition def = registry.get(bizType);
        vo.setBizTypeName(def == null ? bizType : def.getName());
        vo.setBizId(id);
        vo.setBizNo(bizNo);
        vo.setTitle(title);
        vo.setCompanyUscc(companyUscc);
        vo.setCompanyName(companyName);
        vo.setStatus(status);
        vo.setStatusText(statusText(status));
        vo.setRequestedByName(requestedByName);
        vo.setRequestedAt(requestedAt);
        vo.setActions(new ArrayList<>());
        vo.setFlowActive(ctx.hasInstance(bizNo));
        WorkflowStep step = def == null ? null : def.currentStep(status);
        vo.setStepName(step == null ? statusText(status) : step.getName());
        vo.setNextHandler(step == null ? null : step.getActorLabel());
        return vo;
    }

    /**
     * 当前环节是否轮到我动手：挂了流程实例的单只认「流程当前环节办理人=我」（单据上的 assignee 列
     * 只是提交时的快照，节点流转后不再代表当前处理人）；历史存量单回落指派列——
     * 未指派或指派账号已停用/不存在都算无人认领，持证审批人可接管，防单据因处理人离职卡死。
     */
    private boolean mayAct(Board ctx, String bizNo, Long assigneeUserId) {
        if (ctx.isHandler(bizNo)) {
            return true;
        }
        return !ctx.hasInstance(bizNo)
                && (assigneeUserId == null
                || Objects.equals(ctx.me, assigneeUserId)
                || !ctx.enabledUsers.contains(assigneeUserId));
    }

    /** 归组；"看得见但与我无关"的噪声行直接丢弃 */
    private Item classify(WorkflowTodoVO vo, boolean originator, boolean handledByMe, boolean waiting) {
        if (vo.getActions().stream().anyMatch(SELF_SERVE_ACTIONS::contains)) {
            return new Item(vo, Bucket.TODO);
        }
        if (originator && waiting) {
            return new Item(vo, Bucket.INITIATED);
        }
        if (originator || handledByMe) {
            return new Item(vo, Bucket.DONE);
        }
        return null;
    }

    private void add(Board ctx, Item item) {
        if (item == null) {
            return;
        }
        WorkflowTodoVO vo = item.vo();
        decorate(vo);
        switch (item.bucket()) {
            case TODO -> ctx.pending.add(vo);
            case INITIATED -> ctx.initiated.add(vo);
            case DONE -> ctx.done.add(vo);
        }
    }

    /** 补齐主按钮文案（旧前端只读 actionType/actionLabel）与深链 route */
    private void decorate(WorkflowTodoVO vo) {
        List<String> actions = vo.getActions();
        if (actions.contains(ACTION_APPROVE)) {
            vo.setActionType("APPROVE");
            vo.setActionLabel("去审批");
        } else if (actions.contains(ACTION_VOTE)) {
            vo.setActionType("VOTE");
            vo.setActionLabel("去投票");
        } else if (actions.contains(ACTION_ISSUE)) {
            vo.setActionType("ISSUE");
            vo.setActionLabel("去签发");
        } else if (actions.contains(ACTION_EXECUTE_REBUILD)) {
            vo.setActionType("EXECUTE");
            vo.setActionLabel("去执行");
        } else if (actions.contains(ACTION_RECEIPT)) {
            vo.setActionType("RECEIPT");
            vo.setActionLabel("领回执");
        } else {
            vo.setActionType("VIEW");
            vo.setActionLabel("查看");
        }

        String route = switch (vo.getBizType()) {
            case BIZ_CERT_APPLICATION -> "/applications?applyId=" + vo.getBizId();
            case BIZ_CERT_OP -> "/applications?certOpId=" + vo.getBizId();
            case BIZ_COMPANY_OP -> "/company-ops?opId=" + vo.getBizId();
            case BIZ_SUB_CA_REBUILD -> "/approval-center?ticketId=" + vo.getBizId();
            default -> null;
        };
        if (BIZ_SUB_CA_REBUILD.equals(vo.getBizType()) && actions.contains(ACTION_EXECUTE_REBUILD)) {
            route = route + "&action=execute";
        }
        vo.setRoute(route);
    }

    /** 详情闸门：按看板同一套规则建行，建不出即视为无权查看 */
    private Item requireRow(Board ctx, String bizType, Long bizId) {
        String type = bizType == null ? "" : bizType.trim().toUpperCase();
        Item item = switch (type) {
            case BIZ_CERT_APPLICATION -> {
                CertApplication app = applicationMapper.selectById(bizId);
                if (app == null) {
                    throw new IllegalArgumentException("申请单不存在（id=" + bizId + "）");
                }
                ctx.loadFlowState(List.of(app.getApplyNo()));
                yield appItem(ctx, app);
            }
            case BIZ_CERT_OP -> {
                CaCertOpTicket ticket = certOpMapper.selectById(bizId);
                if (ticket == null) {
                    throw new IllegalArgumentException("操作单不存在（id=" + bizId + "）");
                }
                ctx.loadFlowState(List.of(ticket.getOpNo()));
                yield certOpItem(ctx, ticket);
            }
            case BIZ_COMPANY_OP -> {
                CompanyOpTicket ticket = companyOpMapper.selectById(bizId);
                if (ticket == null) {
                    throw new IllegalArgumentException("变更单不存在（id=" + bizId + "）");
                }
                ctx.loadFlowState(List.of(ticket.getOpNo()));
                yield companyOpItem(ctx, ticket);
            }
            case BIZ_SUB_CA_REBUILD -> {
                CaSubCaTicket ticket = subCaMapper.selectById(bizId);
                if (ticket == null) {
                    throw new IllegalArgumentException("重建申请不存在（id=" + bizId + "）");
                }
                ctx.loadFlowState(List.of(ticket.getOpNo()));
                yield subCaItem(ctx, ticket);
            }
            default -> throw new IllegalArgumentException("未知业务类型：" + bizType);
        };
        if (item == null) {
            throw new SecurityException("该单据不在您的待办范围内，无法查看详情");
        }
        decorate(item.vo());
        return item;
    }

    /** 批量补齐公司名称与模板编码：整块看板各一次 in 查询，绝不逐行回查 */
    private void enrich(List<WorkflowTodoVO> rows, Board ctx) {
        fillCompanyNames(rows, ctx);
        fillTemplateCodes(rows, ctx);
    }

    private void fillCompanyNames(List<WorkflowTodoVO> rows, Board ctx) {
        Set<String> missing = new HashSet<>();
        for (WorkflowTodoVO v : rows) {
            if (!notBlank(v.getCompanyName()) && notBlank(v.getCompanyUscc())
                    && !ctx.companyNameByUscc.containsKey(v.getCompanyUscc())) {
                missing.add(v.getCompanyUscc());
            }
        }
        if (!missing.isEmpty()) {
            companyMapper.selectList(new LambdaQueryWrapper<CaCompany>()
                            .select(CaCompany::getUscc, CaCompany::getCompanyName)
                            .in(CaCompany::getUscc, missing))
                    .forEach(c -> ctx.companyNameByUscc.put(c.getUscc(), c.getCompanyName()));
        }
        for (WorkflowTodoVO v : rows) {
            if (!notBlank(v.getCompanyName())) {
                v.setCompanyName(ctx.companyNameByUscc.get(v.getCompanyUscc()));
            }
        }
    }

    private void fillTemplateCodes(List<WorkflowTodoVO> rows, Board ctx) {
        Set<Long> missing = new HashSet<>();
        for (WorkflowTodoVO v : rows) {
            Long templateId = templateIdOf(v, ctx);
            if (templateId != null && !ctx.templateCodeById.containsKey(templateId)) {
                missing.add(templateId);
            }
        }
        if (!missing.isEmpty()) {
            certTemplateMapper.selectList(new LambdaQueryWrapper<CertTemplate>()
                            .select(CertTemplate::getId, CertTemplate::getTemplateCode)
                            .in(CertTemplate::getId, missing))
                    .forEach(t -> ctx.templateCodeById.put(t.getId(), t.getTemplateCode()));
        }
        for (WorkflowTodoVO v : rows) {
            Long templateId = templateIdOf(v, ctx);
            if (templateId != null) {
                v.setTemplateCode(ctx.templateCodeById.get(templateId));
            }
        }
    }

    private Long templateIdOf(WorkflowTodoVO v, Board ctx) {
        return BIZ_CERT_APPLICATION.equals(v.getBizType()) ? ctx.templateIdByBizId.get(v.getBizNo()) : null;
    }

    /**
     * 流程轨迹：节点、办理人真实姓名、通过/驳回、意见、时间。
     * 只出 {@code collaborator}（真实姓名快照）——引擎历史表的 {@code approver} 存登录账号，禁止下发。
     */
    private List<WorkflowFlowHistoryVO> flowHistory(String businessId) {
        if (!notBlank(businessId)) {
            return List.of();
        }
        List<WorkflowFlowHistoryVO> out = new ArrayList<>();
        for (FlowHisTask h : engine.history(businessId)) {
            WorkflowFlowHistoryVO v = new WorkflowFlowHistoryVO();
            v.setNodeCode(blankToNull(h.getNodeCode()));
            v.setNodeName(notBlank(h.getNodeName()) ? h.getNodeName() : blankToNull(h.getFlowName()));
            v.setHandlerName(blankToNull(h.getCollaborator()));
            v.setResult(blankToNull(h.getFlowStatus()));
            v.setResultText(flowResultText(h.getFlowStatus()));
            v.setMessage(blankToNull(h.getMessage()));
            v.setTime(fmt(h.getCreateTime()));
            out.add(v);
        }
        return out;
    }

    private String flowResultText(String result) {
        if (!notBlank(result)) {
            return null;
        }
        return switch (result.toUpperCase()) {
            case "PASS" -> "通过";
            case "REJECT" -> "驳回";
            case "CANCELED" -> "已作废";
            default -> result;
        };
    }

    /**
     * 状态中文（各流状态机不同，但状态码字面一致，按通用口径翻译）
     */
    private String statusText(String status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case "PENDING" -> "待处理";
            case "APPROVED" -> "已批准";
            case "REJECTED" -> "已驳回";
            case "CANCELLED" -> "已撤销";
            case "EXECUTED" -> "已执行";
            case "ISSUED" -> "已签发";
            default -> status;
        };
    }

    /** 逗号分隔的用户 id 串（库内以 String.join(",") 写入，无空格） */
    private List<Long> splitIds(String ids) {
        if (!notBlank(ids)) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String part : ids.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.valueOf(s));
            } catch (NumberFormatException ignored) {
                // 脏数据（历史可能存账号）忽略
            }
        }
        return out;
    }

    private String joinNames(Collection<String> names) {
        return names.stream().filter(this::notBlank).distinct().collect(Collectors.joining("、"));
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }

    private String certOpTitle(CaCertOpTicket t) {
        CertOpTypeEnum type = CertOpTypeEnum.fromCode(t.getOpType());
        String label = type == null ? t.getOpType() : type.getLabel();
        return label + " " + (t.getCertSubjectCn() == null ? "" : t.getCertSubjectCn());
    }

    private String companyOpTitle(CompanyOpTicket t) {
        CompanyOpTypeEnum type = CompanyOpTypeEnum.fromCode(t.getOpType());
        String label = type == null ? t.getOpType() : type.getLabel();
        String target = t.getCompanyName() == null ? t.getCompanyUscc() : t.getCompanyName();
        return label + " " + target;
    }

    /**
     * 时间统一格式化为 {@code yyyy-MM-dd HH:mm:ss}
     */
    private String fmt(LocalDateTime t) {
        if (t == null) {
            return null;
        }
        String s = t.toString().replace('T', ' ');
        return s.length() > 19 ? s.substring(0, 19) : s;
    }

    /** 一条待办 + 它该落进看板的哪个分组 */
    private record Item(WorkflowTodoVO vo, Bucket bucket) {
    }

    private enum Bucket {
        TODO, INITIATED, DONE
    }

    /**
     * 单次请求的取数上下文：流程侧数据集中取、按单号批量回查后复用，
     * 保证「一次看板 = 1 次 myPendingTasks + 每组单号最多 4 次批量 in 查询」。
     */
    private final class Board {

        private final Long me;
        private final Set<String> perms;
        /** 当前环节派给我的流程业务单号（唯一口径，来自引擎，一次取全） */
        private final Set<String> flowMine = new HashSet<>();
        /** 单号 → 是否已有流程实例 */
        private final Map<String, Boolean> flowActive = new HashMap<>();
        /** 单号 → 当前环节办理人姓名（多人以「、」连接） */
        private final Map<String, String> handlerNames = new HashMap<>();
        private final Set<String> flowLoaded = new HashSet<>();

        /** 在岗账号 id 全集（审批人池规模很小，一次取回，用于判「指派人是否已停用」） */
        private final Set<Long> enabledUsers;

        private final Set<String> holderCompanies;
        private final Set<Long> votedSubCaTickets;
        private final Set<Long> votedCompanyOps;
        private final Map<String, Long> templateIdByBizId = new HashMap<>();
        private final Map<Long, String> templateCodeById = new HashMap<>();
        private final Map<String, String> companyNameByUscc = new HashMap<>();

        private final List<WorkflowTodoVO> pending = new ArrayList<>();
        private final List<WorkflowTodoVO> initiated = new ArrayList<>();
        private final List<WorkflowTodoVO> done = new ArrayList<>();

        private final boolean canAppApprove;
        private final boolean canAppReject;
        private final boolean canAppIssue;
        private final boolean canAppCancel;
        private final boolean canAppUrge;
        private final boolean canCertOpApprove;
        private final boolean canCompanyOpApprove;
        private final boolean canCompanyOpApply;
        private final boolean canSubCaApply;
        private final boolean canSubCaExecute;

        private Board(LoginUser viewer) {
            this.me = viewer.getUserId();
            this.perms = new HashSet<>(viewer.getPermissions());
            // 流程待办只取一次，四条流共用同一份单号集合做可见性判定
            for (TaskVO task : engine.myPendingTasks()) {
                if (notBlank(task.getBusinessId())) {
                    flowMine.add(task.getBusinessId());
                }
            }
            this.canAppApprove = hasPerm(PermissionCatalog.APP_APPROVE);
            this.canAppReject = hasPerm(PermissionCatalog.APP_REJECT);
            this.canAppIssue = hasPerm(PermissionCatalog.APP_ISSUE);
            this.canAppCancel = hasPerm(PermissionCatalog.APP_CANCEL);
            this.canAppUrge = hasPerm(PermissionCatalog.APP_URGE);
            this.canCertOpApprove = hasPerm(PermissionCatalog.CERT_OP_APPROVE);
            this.canCompanyOpApprove = hasPerm(PermissionCatalog.COMPANY_OP_APPROVE);
            this.canCompanyOpApply = hasPerm(PermissionCatalog.COMPANY_OP_APPLY);
            this.canSubCaApply = hasPerm(PermissionCatalog.CA_SUBCA_REBUILD_APPLY);
            this.canSubCaExecute = hasPerm(PermissionCatalog.CA_SUBCA_REBUILD_EXECUTE);
            this.enabledUsers = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .select(SysUser::getId)
                            .eq(SysUser::getStatus, 1))
                    .stream().map(SysUser::getId).collect(Collectors.toSet());
            this.holderCompanies = keyShareMapper.selectList(new LambdaQueryWrapper<CompanyKeyShare>()
                            .select(CompanyKeyShare::getCompanyUscc)
                            .eq(CompanyKeyShare::getHolderUserId, me))
                    .stream().map(CompanyKeyShare::getCompanyUscc).collect(Collectors.toSet());
            this.votedSubCaTickets = subCaApprovalMapper.selectList(new LambdaQueryWrapper<CaSubCaApproval>()
                            .select(CaSubCaApproval::getTicketId)
                            .eq(CaSubCaApproval::getApproverUserId, me))
                    .stream().map(CaSubCaApproval::getTicketId).collect(Collectors.toSet());
            this.votedCompanyOps = companyOpVoteMapper.selectList(new LambdaQueryWrapper<CompanyOpVote>()
                            .select(CompanyOpVote::getTicketId)
                            .eq(CompanyOpVote::getVoterUserId, me))
                    .stream().map(CompanyOpVote::getTicketId).collect(Collectors.toSet());
        }

        private boolean hasPerm(String code) {
            return perms.contains(code);
        }

        private boolean isHandler(String bizNo) {
            return bizNo != null && flowMine.contains(bizNo);
        }

        private boolean hasInstance(String bizNo) {
            return bizNo != null && Boolean.TRUE.equals(flowActive.get(bizNo));
        }

        /** 当前处理人姓名：流程侧派到人就用流程侧，否则回落单据上的指派姓名列 */
        private String currentHandler(String bizNo, String fallback) {
            String names = bizNo == null ? null : handlerNames.get(bizNo);
            return notBlank(names) ? names : blankToNull(fallback);
        }

        /**
         * 按单号批量回查流程实例与当前环节办理人：实例存在性 → 待办任务 → 任务办理人明细 → 用户姓名，
         * 最多 4 次 in 查询；已加载过的单号跳过，故详情接口只多付一批。
         */
        private void loadFlowState(Collection<String> bizNos) {
            List<String> todo = new ArrayList<>();
            for (String bizNo : bizNos) {
                if (notBlank(bizNo) && flowLoaded.add(bizNo)) {
                    todo.add(bizNo);
                }
            }
            if (todo.isEmpty()) {
                return;
            }
            Set<String> started = flowInstanceMapper.selectList(new LambdaQueryWrapper<FlowInstance>()
                            .select(FlowInstance::getBusinessId)
                            .in(FlowInstance::getBusinessId, todo))
                    .stream().map(FlowInstance::getBusinessId).collect(Collectors.toSet());
            for (String bizNo : todo) {
                flowActive.put(bizNo, started.contains(bizNo));
            }

            List<FlowTask> tasks = flowTaskMapper.selectList(new LambdaQueryWrapper<FlowTask>()
                    .in(FlowTask::getBusinessId, todo)
                    .eq(FlowTask::getFlowStatus, FLOW_TASK_PENDING));
            if (tasks.isEmpty()) {
                return;
            }
            Map<Long, String> bizNoByTaskId = new HashMap<>();
            for (FlowTask t : tasks) {
                bizNoByTaskId.putIfAbsent(t.getId(), t.getBusinessId());
            }
            List<FlowUser> links = flowUserMapper.selectList(new LambdaQueryWrapper<FlowUser>()
                    .in(FlowUser::getAssociated, bizNoByTaskId.keySet()));
            if (links.isEmpty()) {
                return;
            }
            Set<Long> userIds = new HashSet<>();
            Map<String, Set<Long>> handlers = new HashMap<>();
            for (FlowUser fu : links) {
                String bizNo = bizNoByTaskId.get(fu.getAssociated());
                if (bizNo == null) {
                    continue;
                }
                try {
                    Long userId = Long.valueOf(fu.getProcessedBy());
                    userIds.add(userId);
                    handlers.computeIfAbsent(bizNo, k -> new LinkedHashSet<>()).add(userId);
                } catch (NumberFormatException ignored) {
                    // 历史数据可能存账号，忽略
                }
            }
            if (userIds.isEmpty()) {
                return;
            }
            Map<Long, String> names = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .select(SysUser::getId, SysUser::getRealName)
                            .in(SysUser::getId, userIds))
                    .stream().filter(u -> notBlank(u.getRealName()))
                    .collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));
            handlers.forEach((bizNo, ids) -> {
                String joined = joinNames(ids.stream().map(names::get).toList());
                if (notBlank(joined)) {
                    handlerNames.put(bizNo, joined);
                }
            });
        }
    }
}
