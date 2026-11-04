package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.SubCaRebuildApplyDTO;
import com.csssl.dto.SubCaRebuildDecisionDTO;
import com.csssl.dto.SubCaRebuildExecuteDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.CaSubCaApproval;
import com.csssl.entity.CaSubCaTicket;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CompanyKeyShare;
import com.csssl.entity.LoginUser;
import com.csssl.enums.TicketRebuildStatusEnum;
import com.csssl.mapper.CaSubCaApprovalMapper;
import com.csssl.mapper.CaSubCaTicketMapper;
import com.csssl.mapper.CompanyKeyShareMapper;
import com.csssl.util.ShamirUtil;
import com.csssl.vo.CaShareOverviewVO;
import com.csssl.vo.CaSubCaApprovalVO;
import com.csssl.vo.CaSubCaHolderVO;
import com.csssl.vo.CaSubCaTicketVO;
import com.csssl.vo.KeyShareMineVO;
import com.csssl.workflow.WorkflowEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 二级CA 重建申请单生命周期：发起 → 5 名密钥保管人审批（3-of-5 任意 3 人同意即放行，任一驳回即否决）
 * → 批准后粘贴 ≥3 份额加载根私钥并重建二级CA。
 *
 * <p>审批人限定为该公司 {@code ssl_company_key_share} 中的 5 名保管人（一人一票）；审批动作本身不依赖额外权限点，
 * 仅校验"是否为该公司保管人 + 是否已投票"。发起需 {@code ca:subca:rebuild:apply}，执行需
 * {@code ca:subca:rebuild:execute}（见控制器）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaSubCaRebuildService {

    private static final DateTimeFormatter OP_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 工作流定义编码：提交即起流程，保管人票签由流程节点比例配置决定 */
    private static final String FLOW_CODE = "subcaRebuildFlow";

    private final CaSubCaTicketMapper ticketMapper;
    private final CaSubCaApprovalMapper approvalMapper;
    private final CompanyKeyShareMapper keyShareMapper;
    private final CaService caService;
    private final CompanyService companyService;
    private final AuditService auditService;
    private final MessageService messageService;
    private final ApplicationTrailService trailService;
    private final ObjectMapper objectMapper;
    private final WorkflowEngineService engine;

    /**
     * 发起二级CA 重建申请：校验公司已指定 5 名保管人、无同类待审单；创建 PENDING 单并通知全部保管人。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO apply(SubCaRebuildApplyDTO dto, LoginUser loginUser, String ip) {
        String companyUscc = CompanyService.normalizeUscc(dto.getCompanyUscc());
        companyService.requireActive(companyUscc);
        String reason = dto.getReason() == null || dto.getReason().isBlank() ? null : dto.getReason().trim();
        if (reason == null) {
            throw new IllegalArgumentException("重建事由不能为空（审批依据）");
        }
        List<CompanyKeyShare> holders = caService.listKeyShareHolders(companyUscc);
        if (holders.size() != CaService.SHARE_TOTAL) {
            throw new IllegalArgumentException("该公司尚未指定 " + CaService.SHARE_TOTAL
                    + " 名密钥份额保管人，无法发起重建申请（请先在建根/开公司时指定或手动指派）");
        }
        long pending = ticketMapper.selectCount(new LambdaQueryWrapper<CaSubCaTicket>()
                .eq(CaSubCaTicket::getCompanyUscc, companyUscc)
                .eq(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.PENDING.getCode()));
        if (pending > 0) {
            throw new IllegalArgumentException("该公司已有待审批的二级CA重建申请，请等待审批完成或先撤回");
        }
        CaCompany company = companyService.get(companyUscc);

        CaSubCaTicket ticket = new CaSubCaTicket();
        ticket.setOpNo("RK" + OP_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        ticket.setCompanyUscc(companyUscc);
        ticket.setCompanyName(company.getCompanyName());
        ticket.setReason(reason);
        ticket.setRequestedByUserId(loginUser.getUserId());
        ticket.setRequestedByName(loginUser.getRealName());
        ticket.setRequestedAt(LocalDateTime.now());
        ticket.setThreshold(CaService.SHARE_THRESHOLD);
        ticket.setStatus(TicketRebuildStatusEnum.PENDING.getCode());
        ticket.setApprovedCount(0);
        ticket.setRejectedCount(0);
        ticket.setCreatedAt(LocalDateTime.now());
        ticketMapper.insert(ticket);
        // 提交即起流程：保管人票签节点按流程定义的比例配置推进（与业务门限同一份证据）
        engine.startForBusiness(FLOW_CODE, ticket.getOpNo());

        // 通知全部保管人（把申请"打到"这 5 个人）
        for (CompanyKeyShare h : holders) {
            messageService.send(companyUscc, h.getHolderUserId(), h.getHolderRealName(), loginUser,
                    "SUBCA_REBUILD", "二级CA重建申请待审批",
                    "公司「" + company.getCompanyName() + "」发起二级CA重建申请（单号 " + ticket.getOpNo()
                            + "），请登录审批（需 " + CaService.SHARE_THRESHOLD + "/"
                            + CaService.SHARE_TOTAL + " 名保管人同意）", "CA_SUBCA_REBUILD", String.valueOf(ticket.getId()));
        }
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "SUBMIT",
                loginUser, null, ticket.getStatus(),
                "发起二级CA重建申请（" + CaService.SHARE_THRESHOLD + "/" + CaService.SHARE_TOTAL + " 门限）");
        auditService.record(loginUser, "SUBCA_REBUILD_APPLY", ticket.getOpNo(),
                "发起二级CA重建申请：" + company.getCompanyName() + "（" + companyUscc + "，事由：" + reason + "）", ip, companyUscc);
        log.info("[二级CA重建][{}] 发起 {} 目标={}", ticket.getOpNo(), companyUscc);
        return detail(ticket.getId(), loginUser);
    }

    /**
     * 分页查询（status/keyword 精确/模糊；pendingForMe=true 时仅返回当前登录人作为保管人且尚未投票的待审单）
     */
    public Page<CaSubCaTicketVO> page(long pageNo, long pageSize, String status, String keyword,
                                      String companyUscc, boolean pendingForMe, LoginUser loginUser) {
        LambdaQueryWrapper<CaSubCaTicket> qw = new LambdaQueryWrapper<>();
        if (companyUscc != null && !companyUscc.isBlank()) {
            qw.eq(CaSubCaTicket::getCompanyUscc, CompanyService.normalizeUscc(companyUscc));
        }
        if (status != null && !status.isBlank()) {
            qw.eq(CaSubCaTicket::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(CaSubCaTicket::getOpNo, kw).or()
                    .like(CaSubCaTicket::getCompanyUscc, kw).or().like(CaSubCaTicket::getCompanyName, kw));
        }
        List<Long> pendingIds = null;
        if (pendingForMe) {
            Long me = loginUser == null ? null : loginUser.getUserId();
            if (me == null) {
                return emptyPage(pageNo, pageSize);
            }
            // 当前登录人作为保管人的公司集合
            List<CompanyKeyShare> mine = keyShareMapper.selectList(new LambdaQueryWrapper<CompanyKeyShare>()
                    .eq(CompanyKeyShare::getHolderUserId, me));
            if (mine.isEmpty()) {
                return emptyPage(pageNo, pageSize);
            }
            Set<String> myCompanys = mine.stream().map(CompanyKeyShare::getCompanyUscc).collect(Collectors.toSet());
            // 这些公司的 PENDING 单
            List<CaSubCaTicket> candidates = ticketMapper.selectList(new LambdaQueryWrapper<CaSubCaTicket>()
                    .in(CaSubCaTicket::getCompanyUscc, myCompanys)
                    .eq(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.PENDING.getCode()));
            pendingIds = candidates.stream()
                    .filter(t -> approvalMapper.selectCount(new LambdaQueryWrapper<CaSubCaApproval>()
                            .eq(CaSubCaApproval::getTicketId, t.getId())
                            .eq(CaSubCaApproval::getApproverUserId, me)) == 0)
                    .map(CaSubCaTicket::getId)
                    .collect(Collectors.toList());
            if (pendingIds.isEmpty()) {
                return emptyPage(pageNo, pageSize);
            }
            qw.in(CaSubCaTicket::getId, pendingIds);
        }
        qw.orderByDesc(CaSubCaTicket::getCreatedAt);
        Page<CaSubCaTicket> page = ticketMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        Page<CaSubCaTicketVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(t -> toVo(t, loginUser)).collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 当前登录人作为保管人且尚未投票的待审单（轻量轮询入口）
     */
    public List<CaSubCaTicketVO> pendingForMe(LoginUser actor) {
        Page<CaSubCaTicketVO> p = page(1, Integer.MAX_VALUE, null, null, null, true, actor);
        return p.getRecords();
    }

    public CaSubCaTicketVO detail(Long id, LoginUser actor) {
        CaSubCaTicket ticket = get(id);
        return toVo(ticket, actor);
    }

    /**
     * 审批同意（须为该公司保管人且未投票；任一驳回即否决，同意达阈值即批准）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO approve(Long id, SubCaRebuildDecisionDTO body, LoginUser loginUser, String ip) {
        CaSubCaTicket ticket = checkPending(id);
        assertHolder(ticket.getCompanyUscc(), loginUser.getUserId());
        assertNotDecided(id, loginUser.getUserId());
        CaCompany company = companyService.get(ticket.getCompanyUscc());

        // 审批同意须提交本人持有的根私钥份额（秘钥片）：3 名保管人通过即收集到 ≥3 份，用于执行重建解封根私钥
        String shareJson = body.getShareJson();
        if (shareJson == null || shareJson.isBlank()) {
            throw new IllegalArgumentException("审批同意须提交你本人持有的根私钥份额（秘钥片）：请粘贴「我的密钥份额」中属于你的那份份额 JSON");
        }
        CompanyKeyShare mine = caService.readShareForHolder(ticket.getCompanyUscc(), loginUser.getUserId());
        if (mine == null || mine.getShareJson() == null || mine.getShareJson().isBlank()) {
            throw new IllegalStateException("未找到你本人持有的根私钥份额，无法校验提交内容（请确认你已被指定为该公司密钥保管人）");
        }
        validateShareMatchesHolder(shareJson, mine.getShareJson());

        CaSubCaApproval approval = new CaSubCaApproval();
        approval.setTicketId(id);
        approval.setApproverUserId(loginUser.getUserId());
        approval.setApproverRealName(loginUser.getRealName());
        approval.setDecision(TicketRebuildStatusEnum.DECISION_APPROVED.getCode());
        approval.setComment(blankToNull(body.getComment()));
        approval.setShareJson(shareJson.trim());
        approval.setDecidedAt(LocalDateTime.now());
        approvalMapper.insert(approval);

        recountAndTransition(ticket);
        // 保管人投票已由业务侧校验（本人份额 + 保管人身份），这里同步推进流程票签节点
        engine.passOnBehalf(ticket.getOpNo(), loginUser, "保管人同意二级CA重建");
        auditService.record(loginUser, "SUBCA_REBUILD_APPROVE", ticket.getOpNo(),
                "同意二级CA重建申请：" + company.getCompanyName() + "（" + ticket.getCompanyUscc() + "）", ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "VOTE",
                loginUser, "PENDING", ticket.getStatus(),
                "保管人同意" + (blankToNull(body.getComment()) == null ? "" : "：" + body.getComment().trim()));
        notifyInitiator(ticket, loginUser, "同意");
        return detail(id, loginUser);
    }

    /**
     * 校验提交的份额确为该保管人本人持有的那份（index 与 yHex 与绑定份额一致），防止误提交他人份额。
     */
    private void validateShareMatchesHolder(String submittedJson, String heldJson) {
        try {
            JsonNode submitted = objectMapper.readTree(submittedJson);
            JsonNode held = objectMapper.readTree(heldJson);
            int subIdx = submitted.has("index") ? submitted.get("index").asInt() : -1;
            int heldIdx = held.has("index") ? held.get("index").asInt() : -1;
            String subY = submitted.has("yHex") ? submitted.get("yHex").asText() : "";
            String heldY = held.has("yHex") ? held.get("yHex").asText() : "";
            if (subIdx != heldIdx || !subY.equalsIgnoreCase(heldY)) {
                throw new IllegalArgumentException("提交的份额与你本人持有的保管人份额不一致（你持有的是第 " + heldIdx + " 份），请确认粘贴的是自己的份额");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("份额 JSON 解析失败，请粘贴完整的份额 JSON（含 index/total/threshold/yHex）");
        }
    }

    /**
     * 审批驳回（须为该公司保管人且未投票；意见必填；任一驳回即否决）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO reject(Long id, SubCaRebuildDecisionDTO body, LoginUser loginUser, String ip) {
        CaSubCaTicket ticket = checkPending(id);
        assertHolder(ticket.getCompanyUscc(), loginUser.getUserId());
        assertNotDecided(id, loginUser.getUserId());
        String comment = blankToNull(body.getComment());
        if (comment == null) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        CaCompany company = companyService.get(ticket.getCompanyUscc());

        CaSubCaApproval approval = new CaSubCaApproval();
        approval.setTicketId(id);
        approval.setApproverUserId(loginUser.getUserId());
        approval.setApproverRealName(loginUser.getRealName());
        approval.setDecision(TicketRebuildStatusEnum.DECISION_REJECTED.getCode());
        approval.setComment(comment);
        approval.setDecidedAt(LocalDateTime.now());
        approvalMapper.insert(approval);

        recountAndTransition(ticket);
        // 任一驳回即整体否决，流程同步终止
        engine.rejectOnBehalf(ticket.getOpNo(), loginUser, "保管人驳回：" + comment);
        auditService.record(loginUser, "SUBCA_REBUILD_REJECT", ticket.getOpNo(),
                "驳回二级CA重建申请：" + company.getCompanyName() + "（" + ticket.getCompanyUscc() + "，意见：" + comment + "）",
                ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "VOTE",
                loginUser, "PENDING", ticket.getStatus(),
                "保管人驳回：" + comment);
        notifyInitiator(ticket, loginUser, "驳回");
        return detail(id, loginUser);
    }

    /**
     * 撤回申请（仅发起人本人，且仅 PENDING）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO cancel(Long id, LoginUser loginUser, String ip) {
        CaSubCaTicket ticket = checkPending(id);
        if (!Objects.equals(ticket.getRequestedByUserId(), loginUser.getUserId())) {
            throw new SecurityException("仅申请单发起人本人可撤回");
        }
        ticketMapper.update(null, new LambdaUpdateWrapper<CaSubCaTicket>()
                .eq(CaSubCaTicket::getId, id)
                .eq(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.PENDING.getCode())
                .set(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.CANCELLED.getCode()));
        engine.withdrawByBusiness(ticket.getOpNo(), loginUser);
        auditService.record(loginUser, "SUBCA_REBUILD_CANCEL", ticket.getOpNo(),
                "撤回二级CA重建申请：" + ticket.getCompanyName() + "（" + ticket.getCompanyUscc() + "）", ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "CANCEL",
                loginUser, "PENDING", TicketRebuildStatusEnum.CANCELLED.getCode(), "撤回申请");
        return detail(id, loginUser);
    }

    /**
     * 执行重建（须 APPROVED）：提交 ≥3 份额加载根私钥后重建二级CA，标记 EXECUTED。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO execute(Long id, SubCaRebuildExecuteDTO body, LoginUser actor, String ip) {
        CaSubCaTicket ticket = get(id);
        if (!TicketRebuildStatusEnum.APPROVED.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「已批准」的申请可执行重建（当前状态：" + ticket.getStatus() + "）");
        }
        // 优先使用审批阶段已收集的保管人份额（3 名保管人审批时各自提交了本人份额）
        boolean useCollected = body.getUseCollectedShares() != null && body.getUseCollectedShares();
        List<String> shares = body.getShareJsons();
        if (useCollected) {
            shares = collectApprovedShares(id, ticket.getThreshold());
        }
        if (shares == null || shares.size() < CaService.SHARE_THRESHOLD) {
            throw new IllegalArgumentException("至少需要提交 " + CaService.SHARE_THRESHOLD + " 份根私钥份额"
                    + (useCollected ? "（已收集的保管人份额不足，请确认有 " + ticket.getThreshold() + " 名保管人在审批时提交了本人份额）" : ""));
        }
        CaCompany company = companyService.get(ticket.getCompanyUscc());
        // 1) 通过份额解封根私钥（置入内存缓存）
        caService.unsealRootKeyWithShares(ticket.getCompanyUscc(), shares);
        // 2) 强制重建二级CA（用根重新签发）
        caService.rebuildSubCa(ticket.getCompanyUscc());
        // 3) 标记执行
        ticketMapper.update(null, new LambdaUpdateWrapper<CaSubCaTicket>()
                .eq(CaSubCaTicket::getId, id)
                .eq(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.APPROVED.getCode())
                .set(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.EXECUTED.getCode())
                .set(CaSubCaTicket::getExecutedAt, LocalDateTime.now()));
        // 重建执行完成，流程收尾
        engine.finish(ticket.getOpNo(), actor, "用根 CA 重签二级CA完成");
        auditService.record(actor, "SUBCA_REBUILD_EXECUTE", ticket.getOpNo(),
                "执行二级CA重建：" + company.getCompanyName() + "（" + ticket.getCompanyUscc() + "，已用根重新签发）",
                ip, ticket.getCompanyUscc());
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "EXECUTE",
                actor, TicketRebuildStatusEnum.APPROVED.getCode(), TicketRebuildStatusEnum.EXECUTED.getCode(),
                "用根 CA 重签二级CA");
        log.info("[二级CA重建][{}] 已执行重建 目标={} 执行人={}", ticket.getOpNo(), ticket.getCompanyUscc(),
                actor == null ? null : actor.getRealName());
        return detail(id, actor);
    }

    /**
     * 当前登录人自身持有的根私钥份额（仅保管人可见；非保管人返回 null）
     */
    public KeyShareMineVO myShare(String companyUscc, Long userId) {
        CompanyKeyShare share = caService.readShareForHolder(companyUscc, userId);
        if (share == null) {
            return null;
        }
        return toKeyShareMineVO(share);
    }

    /**
     * 某公司全部保管人名单（不含份额明文、不含登录账号，供发起/管理展示）
     */
    public List<CaSubCaHolderVO> holders(String companyUscc) {
        return caService.listKeyShareHolders(companyUscc).stream().map(h -> {
            CaSubCaHolderVO v = new CaSubCaHolderVO();
            v.setShareIndex(h.getShareIndex());
            v.setHolderRealName(h.getHolderRealName());
            return v;
        }).collect(Collectors.toList());
    }

    /**
     * 份额总览（一次调用返回全部，前端不必多次往返）：
     * 保管人名单对有查看权者可见；当前登录人自身份额仅本人可见，非保管人返回 null。
     */
    public CaShareOverviewVO overview(String companyUscc, LoginUser viewer) {
        CaShareOverviewVO vo = new CaShareOverviewVO();
        vo.setHolders(holders(companyUscc));
        vo.setMine(myShare(companyUscc, viewer == null ? null : viewer.getUserId()));
        vo.setThreshold(CaService.SHARE_THRESHOLD);
        vo.setTotal(CaService.SHARE_TOTAL);
        return vo;
    }

    // ==================== 内部方法 ====================

    private void recountAndTransition(CaSubCaTicket ticket) {
        long approved = approvalMapper.selectCount(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticket.getId())
                .eq(CaSubCaApproval::getDecision, TicketRebuildStatusEnum.DECISION_APPROVED.getCode()));
        long rejected = approvalMapper.selectCount(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticket.getId())
                .eq(CaSubCaApproval::getDecision, TicketRebuildStatusEnum.DECISION_REJECTED.getCode()));
        String oldStatus = ticket.getStatus();
        String status;
        if (rejected > 0) {
            status = TicketRebuildStatusEnum.REJECTED.getCode();
        } else if (approved >= ticket.getThreshold()) {
            status = TicketRebuildStatusEnum.APPROVED.getCode();
        } else {
            status = TicketRebuildStatusEnum.PENDING.getCode();
        }
        ticketMapper.update(null, new LambdaUpdateWrapper<CaSubCaTicket>()
                .eq(CaSubCaTicket::getId, ticket.getId())
                .set(CaSubCaTicket::getApprovedCount, (int) approved)
                .set(CaSubCaTicket::getRejectedCount, (int) rejected)
                .set(CaSubCaTicket::getStatus, status));
        ticket.setApprovedCount((int) approved);
        ticket.setRejectedCount((int) rejected);
        ticket.setStatus(status);
        // 状态转为「已批准」时通知发起人：可提交份额解封并重建二级CA
        if (TicketRebuildStatusEnum.APPROVED.is(status) && !TicketRebuildStatusEnum.APPROVED.is(oldStatus)) {
            messageService.send(ticket.getCompanyUscc(), ticket.getRequestedByUserId(), ticket.getRequestedByName(),
                    null,
                    "SUBCA_REBUILD", "二级CA重建申请已审批通过",
                    "申请单 " + ticket.getOpNo() + " 已获 " + approved + "/" + ticket.getThreshold()
                            + " 名保管人同意，请在「消息待办」提交份额解封并重建二级CA。",
                    "CA_SUBCA_REBUILD", String.valueOf(ticket.getId()));
        }
    }

    /**
     * 从已通过（APPROVED）的审批记录中收集保管人份额（去重、按份额编号），凑齐 ≥阈值 份用于解封根私钥。
     */
    private List<String> collectApprovedShares(Long ticketId, int threshold) {
        List<CaSubCaApproval> approved = approvalMapper.selectList(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticketId)
                .eq(CaSubCaApproval::getDecision, TicketRebuildStatusEnum.DECISION_APPROVED.getCode()));
        Set<Integer> indexes = new java.util.HashSet<>();
        List<String> collected = new ArrayList<>();
        for (CaSubCaApproval a : approved) {
            int idx = shareIndexOf(a.getShareJson());
            if (idx > 0 && indexes.add(idx)) {
                collected.add(a.getShareJson().trim());
            }
        }
        if (collected.size() < threshold) {
            throw new IllegalArgumentException("已收集的保管人份额不足 " + threshold + " 份（需 " + threshold
                    + " 名保管人在审批时各提交本人份额），无法解封根私钥");
        }
        return collected;
    }

    /**
     * 份额 JSON 中的编号（越界/损坏返回 -1，调用方按无效份额跳过）。
     */
    private int shareIndexOf(String shareJson) {
        if (shareJson == null || shareJson.isBlank()) {
            return -1;
        }
        try {
            JsonNode n = objectMapper.readTree(shareJson);
            int idx = n.has("index") ? n.get("index").asInt() : -1;
            return idx >= 1 && idx <= CaService.SHARE_TOTAL ? idx : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 当前登录人作为发起人、且已审批通过（APPROVED）待执行的重建申请单（用于「消息待办 → 待我执行」）。
     */
    public List<CaSubCaTicketVO> myExecutable(LoginUser loginUser) {
        Long userId = loginUser == null ? null : loginUser.getUserId();
        List<CaSubCaTicket> list = ticketMapper.selectList(new LambdaQueryWrapper<CaSubCaTicket>()
                .eq(CaSubCaTicket::getRequestedByUserId, userId)
                .eq(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.APPROVED.getCode())
                .orderByDesc(CaSubCaTicket::getCreatedAt));
        return list.stream().map(t -> toVo(t, loginUser)).collect(Collectors.toList());
    }

    /**
     * 审批轨迹（按步时间正序，面向业务用户）：与 RA 申请 / 操作单 / 变更单同一条轨迹表，口径一致。
     */
    public List<ApplicationTrail> listTrail(Long id) {
        CaSubCaTicket ticket = get(id);
        return trailService.listByApplication(ticket.getId(), ticket.getOpNo());
    }

    /**
     * 催办：向尚未投票的保管人推送站内信并补 URGE 轨迹（仅申请单发起人本人操作）。
     * 本流为 3-of-5 多人投票，没有单一审批人，故按「未投票的保管人」逐人通知。
     */
    public void urge(Long id, LoginUser operator, String ip) {
        CaSubCaTicket ticket = get(id);
        if (!TicketRebuildStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的重建申请可催办（当前状态：" + ticket.getStatus() + "）");
        }
        if (!Objects.equals(ticket.getRequestedByUserId(), operator.getUserId())) {
            throw new SecurityException("仅重建申请发起人本人可催办");
        }
        List<CompanyKeyShare> holders = caService.listKeyShareHolders(ticket.getCompanyUscc());
        Set<Long> voted = approvalMapper.selectList(new LambdaQueryWrapper<CaSubCaApproval>()
                        .eq(CaSubCaApproval::getTicketId, ticket.getId()))
                .stream().map(CaSubCaApproval::getApproverUserId).collect(Collectors.toSet());
        List<Long> targets = holders.stream()
                .filter(h -> !voted.contains(h.getHolderUserId()))
                .map(CompanyKeyShare::getHolderUserId)
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalStateException("该申请的保管人已全部投票，无需催办");
        }
        for (CompanyKeyShare h : holders) {
            if (!targets.contains(h.getHolderUserId())) {
                continue;
            }
            messageService.send(ticket.getCompanyUscc(), h.getHolderUserId(), h.getHolderRealName(),
                    operator, "SUBCA_REBUILD_URGE", "二级CA重建申请待审批催办",
                    "申请单 " + ticket.getOpNo() + " 待您审批（发起人：" + ticket.getRequestedByName() + "）",
                    "CA_SUBCA_REBUILD", String.valueOf(ticket.getId()));
        }
        trailService.record(ticket.getCompanyUscc(), ticket.getId(), ticket.getOpNo(), "URGE",
                operator, TicketRebuildStatusEnum.PENDING.getCode(),
                TicketRebuildStatusEnum.PENDING.getCode(), "催办未投票保管人 " + targets.size() + " 人");
        auditService.record(operator, "SUBCA_REBUILD_URGE", ticket.getOpNo(),
                "催办未投票保管人处理重建申请", ip, ticket.getCompanyUscc());
    }

    private void notifyInitiator(CaSubCaTicket ticket, LoginUser actor, String verb) {
        messageService.send(ticket.getCompanyUscc(), ticket.getRequestedByUserId(), ticket.getRequestedByName(), actor,
                "SUBCA_REBUILD", "二级CA重建申请审批结果",
                "申请单 " + ticket.getOpNo() + " 已被 " + (actor == null ? null : actor.getRealName()) + " " + verb,
                "CA_SUBCA_REBUILD", String.valueOf(ticket.getId()));
    }

    private void assertHolder(String companyUscc, Long userId) {
        CompanyKeyShare mine = caService.readShareForHolder(companyUscc, userId);
        if (mine == null) {
            throw new SecurityException("非该公司密钥保管人，无权审批二级CA重建申请（须为指定的 5 名保管人之一）");
        }
    }

    private void assertNotDecided(Long ticketId, Long userId) {
        long done = approvalMapper.selectCount(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticketId)
                .eq(CaSubCaApproval::getApproverUserId, userId));
        if (done > 0) {
            throw new IllegalArgumentException("你已对该申请作出审批，不可重复投票");
        }
    }

    private CaSubCaTicket get(Long id) {
        CaSubCaTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("二级CA重建申请单不存在（id=" + id + "）");
        }
        return ticket;
    }

    private CaSubCaTicket checkPending(Long id) {
        CaSubCaTicket ticket = get(id);
        if (!TicketRebuildStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的申请单可操作（当前状态：" + ticket.getStatus() + "）");
        }
        return ticket;
    }

    private CaSubCaTicketVO toVo(CaSubCaTicket ticket, LoginUser loginUser) {
        CaSubCaTicketVO vo = new CaSubCaTicketVO();
        vo.setId(ticket.getId());
        vo.setOpNo(ticket.getOpNo());
        vo.setCompanyUscc(ticket.getCompanyUscc());
        vo.setCompanyName(ticket.getCompanyName());
        vo.setReason(ticket.getReason());
        vo.setRequestedByName(ticket.getRequestedByName());
        vo.setRequestedAt(ticket.getRequestedAt());
        vo.setThreshold(ticket.getThreshold());
        vo.setStatus(ticket.getStatus());
        vo.setApprovedCount(ticket.getApprovedCount());
        vo.setRejectedCount(ticket.getRejectedCount());
        vo.setExecutedAt(ticket.getExecutedAt());

        List<CompanyKeyShare> holders = caService.listKeyShareHolders(ticket.getCompanyUscc());
        vo.setHolders(holders.stream().map(h -> {
            CaSubCaHolderVO hv = new CaSubCaHolderVO();
            hv.setShareIndex(h.getShareIndex());
            hv.setHolderRealName(h.getHolderRealName());
            return hv;
        }).collect(Collectors.toList()));

        List<CaSubCaApproval> approvals = approvalMapper.selectList(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticket.getId())
                .orderByAsc(CaSubCaApproval::getDecidedAt));
        vo.setApprovals(approvals.stream().map(a -> {
            CaSubCaApprovalVO av = new CaSubCaApprovalVO();
            av.setApproverRealName(a.getApproverRealName());
            av.setDecision(a.getDecision());
            av.setComment(a.getComment());
            av.setDecidedAt(a.getDecidedAt());
            return av;
        }).collect(Collectors.toList()));

        // 当前登录人待办标记（按 user_id 判定"同一人"，账号不参与比较）
        Long me = loginUser == null ? null : loginUser.getUserId();
        boolean isHolder = me != null && holders.stream().anyMatch(h -> me.equals(h.getHolderUserId()));
        boolean decided = me != null && approvals.stream().anyMatch(a -> me.equals(a.getApproverUserId()));
        boolean initiator = Objects.equals(ticket.getRequestedByUserId(), me);
        vo.setRevocable(initiator && TicketRebuildStatusEnum.PENDING.is(ticket.getStatus()));
        vo.setExecutableByMe(initiator && TicketRebuildStatusEnum.APPROVED.is(ticket.getStatus()));
        vo.setMyPendingDecision(isHolder && !decided && TicketRebuildStatusEnum.PENDING.is(ticket.getStatus()));
        vo.setMyDecision(decided ? approvals.stream()
                .filter(a -> me.equals(a.getApproverUserId()))
                .map(CaSubCaApproval::getDecision).findFirst().orElse(null) : null);
        Set<Integer> collectedIndexes = new java.util.HashSet<>();
        for (CaSubCaApproval a : approvals) {
            if (TicketRebuildStatusEnum.DECISION_APPROVED.getCode().equals(a.getDecision())) {
                int idx = shareIndexOf(a.getShareJson());
                if (idx > 0) {
                    collectedIndexes.add(idx);
                }
            }
        }
        vo.setCollectedShareCount(collectedIndexes.size());
        return vo;
    }

    private KeyShareMineVO toKeyShareMineVO(CompanyKeyShare share) {
        KeyShareMineVO vo = new KeyShareMineVO();
        vo.setHolderRealName(share.getHolderRealName());
        vo.setShareJson(share.getShareJson());
        try {
            JsonNode node = objectMapper.readTree(share.getShareJson());
            vo.setIndex(node.has("index") ? node.get("index").asInt() : share.getShareIndex());
            vo.setTotal(node.has("total") ? node.get("total").asInt() : CaService.SHARE_TOTAL);
            vo.setThreshold(node.has("threshold") ? node.get("threshold").asInt() : CaService.SHARE_THRESHOLD);
            vo.setYHex(node.has("yHex") ? node.get("yHex").asText() : null);
        } catch (Exception e) {
            vo.setIndex(share.getShareIndex());
            vo.setTotal(CaService.SHARE_TOTAL);
            vo.setThreshold(CaService.SHARE_THRESHOLD);
        }
        return vo;
    }

    private <T> Page<T> emptyPage(long pageNo, long pageSize) {
        Page<T> p = new Page<>(pageNo, pageSize, 0);
        p.setRecords(new ArrayList<>());
        return p;
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
