package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaCertOpTicket;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CaSubCaApproval;
import com.csssl.entity.CaSubCaTicket;
import com.csssl.entity.CertApplication;
import com.csssl.entity.CompanyKeyShare;
import com.csssl.entity.CompanyOpTicket;
import com.csssl.entity.LoginUser;
import com.csssl.enums.CertOpTypeEnum;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.enums.CompanyOpTypeEnum;
import com.csssl.enums.TicketRebuildStatusEnum;
import com.csssl.enums.TicketStatusEnum;
import com.csssl.mapper.CaCertOpTicketMapper;
import com.csssl.mapper.CaSubCaApprovalMapper;
import com.csssl.mapper.CaSubCaTicketMapper;
import com.csssl.mapper.CertApplicationMapper;
import com.csssl.mapper.CompanyKeyShareMapper;
import com.csssl.mapper.CompanyOpTicketMapper;
import com.csssl.util.PermissionCatalog;
import com.csssl.vo.WorkflowTodoBoardVO;
import com.csssl.vo.WorkflowTodoVO;
import com.csssl.workflow.WorkflowDefinition;
import com.csssl.workflow.WorkflowEngineService;
import com.csssl.workflow.WorkflowRegistry;
import com.csssl.workflow.WorkflowStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一待办聚合：把四条审批流（RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建）里
 * 「与我相关」的单据收敛成同一份待办清单，供「审批中心聚合页」一屏看完。
 *
 * <p>口径与各业务流自身完全一致——本服务只做"取数 + 统一成一条记录"，不重新定义
 * 谁能审、谁是下一处理人（那些仍由各流的 checkAssignee / markReviewable 决定）：
 * <ul>
 *   <li><b>待我处理</b>：本单当前环节的处理人就是我（RA/操作单/变更单=被指派人；
 *       SUB_CA=我是该公司保管人且尚未投票）；另含"我该签发""我该执行重建"两种执行型待办；</li>
 *   <li><b>我发起的</b>：requestedBy/appliedBy=我 且尚未终结；</li>
 *   <li><b>已办</b>：我已审批/投票/签发/执行过的单。</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class WorkflowTodoService {

    private static final int MAX_ROWS = 100;

    private final CertApplicationMapper applicationMapper;
    private final CaCertOpTicketMapper certOpMapper;
    private final CompanyOpTicketMapper companyOpMapper;
    private final CaSubCaTicketMapper subCaMapper;
    private final CaSubCaApprovalMapper subCaApprovalMapper;
    private final CompanyKeyShareMapper keyShareMapper;
    private final WorkflowRegistry registry;
    private final WorkflowEngineService engine;

    /**
     * 聚合当前登录人的待办看板。
     */
    public WorkflowTodoBoardVO board(LoginUser viewer) {
        Long me = viewer.getUserId();
        String scope = null; // 平台管理员看跨公司全量待办

        List<WorkflowTodoVO> pending = new ArrayList<>();
        List<WorkflowTodoVO> initiated = new ArrayList<>();
        List<WorkflowTodoVO> done = new ArrayList<>();

        collectApplications(viewer, scope, me, pending, initiated, done);
        collectCertOps(viewer, scope, me, pending, initiated, done);
        collectCompanyOps(viewer, scope, me, pending, initiated, done);
        collectSubCa(viewer, scope, me, pending, initiated, done);

        Comparator<WorkflowTodoVO> byTime = Comparator.comparing(
                v -> v.getRequestedAt() == null ? "" : v.getRequestedAt(), Comparator.reverseOrder());
        pending.sort(byTime);
        initiated.sort(byTime);
        done.sort(byTime);

        WorkflowTodoBoardVO vo = new WorkflowTodoBoardVO();
        vo.setPending(pending);
        vo.setInitiated(initiated);
        vo.setDone(done);
        return vo;
    }

    // ==================== RA 证书申请 ====================

    private void collectApplications(LoginUser viewer, String scope, Long me,
                                     List<WorkflowTodoVO> pending, List<WorkflowTodoVO> initiated,
                                     List<WorkflowTodoVO> done) {
        LambdaQueryWrapper<CertApplication> qw = new LambdaQueryWrapper<>();
        if (scope != null && !scope.isBlank()) {
            qw.eq(CertApplication::getCompanyUscc, scope);
        }
        qw.and(w -> w.eq(CertApplication::getAppliedByUserId, me)
                .or().eq(CertApplication::getAssigneeUserId, me)
                .or().eq(CertApplication::getReviewedByUserId, me)
                // 流程驱动后审批人由节点配置决定，待审单对候选办理人放开
                .or().eq(CertApplication::getStatus, CertApplicationStatusEnum.PENDING.getCode()));
        List<CertApplication> rows = applicationMapper.selectList(qw);
        for (CertApplication app : rows) {
            boolean pendingReview = CertApplicationStatusEnum.PENDING.is(app.getStatus())
                    && engine.canHandle(app.getApplyNo(), viewer);
            boolean pendingIssue = CertApplicationStatusEnum.APPROVED.is(app.getStatus())
                    && canIssue(viewer);
            WorkflowTodoVO vo = base("CERT_APPLICATION", app.getId(), app.getApplyNo(),
                    app.getApplicantName(), app.getCompanyUscc(), null,
                    app.getStatus(), app.getAppliedByName(), fmt(app.getAppliedAt()));
            if (pendingReview) {
                vo.setActionType("APPROVE");
                vo.setActionLabel("去审批");
                vo.setRoute("/applications?applyId=" + app.getId());
                pending.add(vo);
            } else if (pendingIssue) {
                vo.setActionType("ISSUE");
                vo.setActionLabel("去签发");
                vo.setRoute("/applications?applyId=" + app.getId());
                pending.add(vo);
            } else if (Objects.equals(me, app.getAppliedByUserId())
                    && !CertApplicationStatusEnum.ISSUED.is(app.getStatus())
                    && !CertApplicationStatusEnum.REJECTED.is(app.getStatus())
                    && !CertApplicationStatusEnum.CANCELLED.is(app.getStatus())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/applications?applyId=" + app.getId());
                initiated.add(vo);
            } else if (Objects.equals(me, app.getReviewedByUserId()) || Objects.equals(me, app.getAssigneeUserId())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/applications?applyId=" + app.getId());
                done.add(vo);
            }
        }
    }

    // ==================== 证书操作单 ====================

    private void collectCertOps(LoginUser viewer, String scope, Long me,
                                List<WorkflowTodoVO> pending, List<WorkflowTodoVO> initiated,
                                List<WorkflowTodoVO> done) {
        LambdaQueryWrapper<CaCertOpTicket> qw = new LambdaQueryWrapper<>();
        if (scope != null && !scope.isBlank()) {
            qw.eq(CaCertOpTicket::getCompanyUscc, scope);
        }
        qw.and(w -> w.eq(CaCertOpTicket::getRequestedByUserId, me)
                .or().eq(CaCertOpTicket::getAssigneeUserId, me)
                .or().eq(CaCertOpTicket::getReviewedByUserId, me)
                .or().eq(CaCertOpTicket::getStatus, TicketStatusEnum.PENDING.getCode()));
        List<CaCertOpTicket> rows = certOpMapper.selectList(qw);
        for (CaCertOpTicket t : rows) {
            WorkflowTodoVO vo = base("CERT_OP", t.getId(), t.getOpNo(),
                    certOpTitle(t), t.getCompanyUscc(), null,
                    t.getStatus(), t.getRequestedByName(), fmt(t.getRequestedAt()));
            boolean pendingReview = TicketStatusEnum.PENDING.is(t.getStatus())
                    && engine.canHandle(t.getOpNo(), viewer);
            if (pendingReview) {
                vo.setActionType("APPROVE");
                vo.setActionLabel("去审批");
                vo.setRoute("/applications?certOpId=" + t.getId());
                pending.add(vo);
            } else if (Objects.equals(me, t.getRequestedByUserId()) && TicketStatusEnum.PENDING.is(t.getStatus())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/applications?certOpId=" + t.getId());
                initiated.add(vo);
            } else if (Objects.equals(me, t.getReviewedByUserId()) || Objects.equals(me, t.getAssigneeUserId())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/applications?certOpId=" + t.getId());
                done.add(vo);
            }
        }
    }

    // ==================== 公司变更单 ====================

    private void collectCompanyOps(LoginUser viewer, String scope, Long me,
                                   List<WorkflowTodoVO> pending, List<WorkflowTodoVO> initiated,
                                   List<WorkflowTodoVO> done) {
        LambdaQueryWrapper<CompanyOpTicket> qw = new LambdaQueryWrapper<>();
        if (scope != null && !scope.isBlank()) {
            qw.eq(CompanyOpTicket::getCompanyUscc, scope);
        }
        qw.and(w -> w.eq(CompanyOpTicket::getRequestedByUserId, me)
                .or().eq(CompanyOpTicket::getAssigneeUserId, me)
                .or().eq(CompanyOpTicket::getReviewedByUserId, me)
                .or(x -> x.eq(CompanyOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                        .ne(CompanyOpTicket::getOpType, CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode())));
        List<CompanyOpTicket> rows = companyOpMapper.selectList(qw);
        for (CompanyOpTicket t : rows) {
            WorkflowTodoVO vo = base("COMPANY_OP", t.getId(), t.getOpNo(),
                    companyOpTitle(t), t.getCompanyUscc(), t.getCompanyName(),
                    t.getStatus(), t.getRequestedByName(), fmt(t.getRequestedAt()));
            boolean pendingReview = TicketStatusEnum.PENDING.is(t.getStatus())
                    && !CompanyOpTypeEnum.REASSIGN_HOLDERS.getCode().equals(t.getOpType())
                    && engine.canHandle(t.getOpNo(), viewer);
            if (pendingReview) {
                vo.setActionType("APPROVE");
                vo.setActionLabel("去审批");
                vo.setRoute("/company-ops?opId=" + t.getId());
                pending.add(vo);
            } else if (Objects.equals(me, t.getRequestedByUserId()) && TicketStatusEnum.PENDING.is(t.getStatus())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/company-ops?opId=" + t.getId());
                initiated.add(vo);
            } else if (Objects.equals(me, t.getReviewedByUserId()) || Objects.equals(me, t.getAssigneeUserId())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/company-ops?opId=" + t.getId());
                done.add(vo);
            }
        }
    }

    // ==================== SUB_CA 重建（3-of-5 保管人投票） ====================

    private void collectSubCa(LoginUser viewer, String scope, Long me,
                              List<WorkflowTodoVO> pending, List<WorkflowTodoVO> initiated,
                              List<WorkflowTodoVO> done) {
        // 我作为保管人的公司集合（投票资格）
        Set<String> myCompanies = keyShareMapper.selectList(new LambdaQueryWrapper<CompanyKeyShare>()
                        .eq(CompanyKeyShare::getHolderUserId, me))
                .stream().map(CompanyKeyShare::getCompanyUscc).collect(Collectors.toSet());

        LambdaQueryWrapper<CaSubCaTicket> qw = new LambdaQueryWrapper<>();
        if (scope != null && !scope.isBlank()) {
            qw.eq(CaSubCaTicket::getCompanyUscc, scope);
        }
        if (myCompanies.isEmpty()) {
            qw.eq(CaSubCaTicket::getRequestedByUserId, me);
        } else {
            qw.and(w -> w.eq(CaSubCaTicket::getRequestedByUserId, me)
                    .or().in(CaSubCaTicket::getCompanyUscc, myCompanies));
        }
        List<CaSubCaTicket> rows = subCaMapper.selectList(qw);

        // 我已投过的票（用于判断"待我投票"与"已办"）
        Set<Long> votedTickets = subCaApprovalMapper.selectList(new LambdaQueryWrapper<CaSubCaApproval>()
                        .eq(CaSubCaApproval::getApproverUserId, me))
                .stream().map(CaSubCaApproval::getTicketId).collect(Collectors.toSet());

        for (CaSubCaTicket t : rows) {
            WorkflowTodoVO vo = base("SUB_CA_REBUILD", t.getId(), t.getOpNo(),
                    t.getReason(), t.getCompanyUscc(), t.getCompanyName(),
                    t.getStatus(), t.getRequestedByName(), fmt(t.getRequestedAt()));
            boolean isHolder = myCompanies.contains(t.getCompanyUscc());
            boolean voted = votedTickets.contains(t.getId());
            boolean iInitiated = Objects.equals(t.getRequestedByUserId(), me);
            if (isHolder && !voted && TicketRebuildStatusEnum.PENDING.is(t.getStatus())) {
                vo.setActionType("VOTE");
                vo.setActionLabel("去投票");
                vo.setRoute("/approval-center?ticketId=" + t.getId());
                pending.add(vo);
            } else if (iInitiated && TicketRebuildStatusEnum.APPROVED.is(t.getStatus())) {
                // 已审批通过、待发起人提交份额解封并重建
                vo.setActionType("EXECUTE");
                vo.setActionLabel("去执行");
                vo.setRoute("/approval-center?ticketId=" + t.getId() + "&action=execute");
                pending.add(vo);
            } else if (iInitiated && TicketRebuildStatusEnum.PENDING.is(t.getStatus())) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/approval-center?ticketId=" + t.getId());
                initiated.add(vo);
            } else if (voted) {
                vo.setActionType("VIEW");
                vo.setActionLabel("查看");
                vo.setRoute("/approval-center?ticketId=" + t.getId());
                done.add(vo);
            }
        }
    }

    // ==================== 公共 ====================

    /**
     * 组装条目骨架，并按工作流定义推导当前环节与当前处理人。
     */
    private WorkflowTodoVO base(String bizType, Long id, String bizNo, String title,
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
        WorkflowStep step = def == null ? null : def.currentStep(status);
        vo.setStepName(step == null ? statusText(status) : step.getName());
        vo.setNextHandler(step == null ? null : step.getActorLabel());
        return vo;
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

    private boolean canIssue(LoginUser viewer) {
        return viewer.getPermissions().contains(PermissionCatalog.APP_ISSUE);
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
    private String fmt(java.time.LocalDateTime t) {
        if (t == null) {
            return null;
        }
        String s = t.toString().replace('T', ' ');
        return s.length() > 19 ? s.substring(0, 19) : s;
    }
}
