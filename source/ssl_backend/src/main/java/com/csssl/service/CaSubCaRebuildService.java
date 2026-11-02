package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.SubCaRebuildApplyDTO;
import com.csssl.dto.SubCaRebuildDecisionDTO;
import com.csssl.dto.SubCaRebuildExecuteDTO;
import com.csssl.entity.CaSubCaApproval;
import com.csssl.entity.CaSubCaTicket;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CompanyKeyShare;
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

    private final CaSubCaTicketMapper ticketMapper;
    private final CaSubCaApprovalMapper approvalMapper;
    private final CompanyKeyShareMapper keyShareMapper;
    private final CaService caService;
    private final CompanyService companyService;
    private final AuditService auditService;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    /**
     * 发起二级CA 重建申请：校验公司已指定 5 名保管人、无同类待审单；创建 PENDING 单并通知全部保管人。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO apply(SubCaRebuildApplyDTO dto, String username, String realName, String ip) {
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
        ticket.setRequestedBy(username);
        ticket.setRequestedByName(realNameOrAccount(realName, username));
        ticket.setRequestedAt(LocalDateTime.now());
        ticket.setThreshold(CaService.SHARE_THRESHOLD);
        ticket.setStatus(TicketRebuildStatusEnum.PENDING.getCode());
        ticket.setApprovedCount(0);
        ticket.setRejectedCount(0);
        ticket.setCreatedAt(LocalDateTime.now());
        ticketMapper.insert(ticket);

        // 通知全部保管人（把申请"打到"这 5 个人）
        for (CompanyKeyShare h : holders) {
            messageService.send(companyUscc, h.getHolderUsername(), h.getHolderRealName(), username,
                    "SUBCA_REBUILD", "二级CA重建申请待审批",
                    "公司「" + company.getCompanyName() + "」发起二级CA重建申请（单号 " + ticket.getOpNo()
                            + "），请登录审批（需 " + CaService.SHARE_THRESHOLD + "/"
                            + CaService.SHARE_TOTAL + " 名保管人同意）", "CA_SUBCA_REBUILD", String.valueOf(ticket.getId()));
        }
        auditService.record(username, "SUBCA_REBUILD_APPLY", ticket.getOpNo(),
                "发起二级CA重建申请：" + company.getCompanyName() + "（" + companyUscc + "，事由：" + reason + "）", ip, companyUscc);
        log.info("[二级CA重建][{}] 发起 {} 目标={} 发起人={}", ticket.getOpNo(), companyUscc, username);
        return detail(ticket.getId(), username);
    }

    /**
     * 分页查询（status/keyword 精确/模糊；pendingForMe=true 时仅返回当前登录人作为保管人且尚未投票的待审单）
     */
    public Page<CaSubCaTicketVO> page(long pageNo, long pageSize, String status, String keyword, boolean pendingForMe, String username) {
        LambdaQueryWrapper<CaSubCaTicket> qw = new LambdaQueryWrapper<>();
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
            // 当前登录人作为保管人的公司集合
            List<CompanyKeyShare> mine = keyShareMapper.selectList(new LambdaQueryWrapper<CompanyKeyShare>()
                    .eq(CompanyKeyShare::getHolderUsername, username));
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
                            .eq(CaSubCaApproval::getApproverUsername, username)) == 0)
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
        voPage.setRecords(page.getRecords().stream().map(t -> toVo(t, username)).collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 当前登录人作为保管人且尚未投票的待审单（轻量轮询入口）
     */
    public List<CaSubCaTicketVO> pendingForMe(String username) {
        Page<CaSubCaTicketVO> p = page(1, Integer.MAX_VALUE, null, null, true, username);
        return p.getRecords();
    }

    public CaSubCaTicketVO detail(Long id, String username) {
        CaSubCaTicket ticket = get(id);
        return toVo(ticket, username);
    }

    /**
     * 审批同意（须为该公司保管人且未投票；任一驳回即否决，同意达阈值即批准）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO approve(Long id, SubCaRebuildDecisionDTO body, String username, String realName, String ip) {
        CaSubCaTicket ticket = checkPending(id);
        assertHolder(ticket.getCompanyUscc(), username);
        assertNotDecided(id, username);
        CaCompany company = companyService.get(ticket.getCompanyUscc());

        CaSubCaApproval approval = new CaSubCaApproval();
        approval.setTicketId(id);
        approval.setApproverUsername(username);
        approval.setApproverRealName(realNameOrAccount(realName, username));
        approval.setDecision(TicketRebuildStatusEnum.DECISION_APPROVED.getCode());
        approval.setComment(blankToNull(body.getComment()));
        approval.setDecidedAt(LocalDateTime.now());
        approvalMapper.insert(approval);

        recountAndTransition(ticket);
        auditService.record(username, "SUBCA_REBUILD_APPROVE", ticket.getOpNo(),
                "同意二级CA重建申请：" + company.getCompanyName() + "（" + ticket.getCompanyUscc() + "）", ip, ticket.getCompanyUscc());
        notifyInitiator(ticket, username, "同意");
        return detail(id, username);
    }

    /**
     * 审批驳回（须为该公司保管人且未投票；意见必填；任一驳回即否决）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO reject(Long id, SubCaRebuildDecisionDTO body, String username, String realName, String ip) {
        CaSubCaTicket ticket = checkPending(id);
        assertHolder(ticket.getCompanyUscc(), username);
        assertNotDecided(id, username);
        String comment = blankToNull(body.getComment());
        if (comment == null) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        CaCompany company = companyService.get(ticket.getCompanyUscc());

        CaSubCaApproval approval = new CaSubCaApproval();
        approval.setTicketId(id);
        approval.setApproverUsername(username);
        approval.setApproverRealName(realNameOrAccount(realName, username));
        approval.setDecision(TicketRebuildStatusEnum.DECISION_REJECTED.getCode());
        approval.setComment(comment);
        approval.setDecidedAt(LocalDateTime.now());
        approvalMapper.insert(approval);

        recountAndTransition(ticket);
        auditService.record(username, "SUBCA_REBUILD_REJECT", ticket.getOpNo(),
                "驳回二级CA重建申请：" + company.getCompanyName() + "（" + ticket.getCompanyUscc() + "，意见：" + comment + "）",
                ip, ticket.getCompanyUscc());
        notifyInitiator(ticket, username, "驳回");
        return detail(id, username);
    }

    /**
     * 撤回申请（仅发起人本人，且仅 PENDING）
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO cancel(Long id, String username, String ip) {
        CaSubCaTicket ticket = checkPending(id);
        if (!Objects.equals(ticket.getRequestedBy(), username)) {
            throw new SecurityException("仅申请单发起人本人可撤回（当前操作人：" + username + "）");
        }
        ticketMapper.update(null, new LambdaUpdateWrapper<CaSubCaTicket>()
                .eq(CaSubCaTicket::getId, id)
                .eq(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.PENDING.getCode())
                .set(CaSubCaTicket::getStatus, TicketRebuildStatusEnum.CANCELLED.getCode()));
        auditService.record(username, "SUBCA_REBUILD_CANCEL", ticket.getOpNo(),
                "撤回二级CA重建申请：" + ticket.getCompanyName() + "（" + ticket.getCompanyUscc() + "）", ip, ticket.getCompanyUscc());
        return detail(id, username);
    }

    /**
     * 执行重建（须 APPROVED）：提交 ≥3 份额加载根私钥后重建二级CA，标记 EXECUTED。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaSubCaTicketVO execute(Long id, SubCaRebuildExecuteDTO body, String username, String realName, String ip) {
        CaSubCaTicket ticket = get(id);
        if (!TicketRebuildStatusEnum.APPROVED.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「已批准」的申请可执行重建（当前状态：" + ticket.getStatus() + "）");
        }
        List<String> shares = body.getShareJsons();
        if (shares == null || shares.size() < CaService.SHARE_THRESHOLD) {
            throw new IllegalArgumentException("至少需要提交 " + CaService.SHARE_THRESHOLD + " 份根私钥份额");
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
        auditService.record(username, "SUBCA_REBUILD_EXECUTE", ticket.getOpNo(),
                "执行二级CA重建：" + company.getCompanyName() + "（" + ticket.getCompanyUscc() + "，已用根重新签发）",
                ip, ticket.getCompanyUscc());
        log.info("[二级CA重建][{}] 已执行重建 目标={} 执行人={}", ticket.getOpNo(), ticket.getCompanyUscc(), username);
        return detail(id, username);
    }

    /**
     * 当前登录人自身持有的根私钥份额（仅保管人可见；非保管人返回 null）
     */
    public KeyShareMineVO myShare(String companyUscc, String username) {
        CompanyKeyShare share = caService.readShareForHolder(companyUscc, username);
        if (share == null) {
            return null;
        }
        return toKeyShareMineVO(share);
    }

    /**
     * 某公司全部保管人名单（不含份额明文，供发起/管理展示）
     */
    public List<CaSubCaHolderVO> holders(String companyUscc) {
        return caService.listKeyShareHolders(companyUscc).stream().map(h -> {
            CaSubCaHolderVO v = new CaSubCaHolderVO();
            v.setShareIndex(h.getShareIndex());
            v.setHolderUsername(h.getHolderUsername());
            v.setHolderRealName(h.getHolderRealName());
            return v;
        }).collect(Collectors.toList());
    }

    /**
     * 份额总览（一次调用返回全部，前端不必多次往返）：
     * 保管人名单对有查看权者可见；当前登录人自身份额仅本人可见，非保管人返回 null。
     */
    public CaShareOverviewVO overview(String companyUscc, String username) {
        CaShareOverviewVO vo = new CaShareOverviewVO();
        vo.setHolders(holders(companyUscc));
        vo.setMine(myShare(companyUscc, username));
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
    }

    private void notifyInitiator(CaSubCaTicket ticket, String actor, String verb) {
        messageService.send(ticket.getCompanyUscc(), ticket.getRequestedBy(), ticket.getRequestedByName(), actor,
                "SUBCA_REBUILD", "二级CA重建申请审批结果",
                "申请单 " + ticket.getOpNo() + " 已被 " + actor + " " + verb, "CA_SUBCA_REBUILD", String.valueOf(ticket.getId()));
    }

    private void assertHolder(String companyUscc, String username) {
        CompanyKeyShare mine = caService.readShareForHolder(companyUscc, username);
        if (mine == null) {
            throw new SecurityException("非该公司密钥保管人，无权审批二级CA重建申请（须为指定的 5 名保管人之一）");
        }
    }

    private void assertNotDecided(Long ticketId, String username) {
        long done = approvalMapper.selectCount(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticketId)
                .eq(CaSubCaApproval::getApproverUsername, username));
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

    private CaSubCaTicketVO toVo(CaSubCaTicket ticket, String username) {
        CaSubCaTicketVO vo = new CaSubCaTicketVO();
        vo.setId(ticket.getId());
        vo.setOpNo(ticket.getOpNo());
        vo.setCompanyUscc(ticket.getCompanyUscc());
        vo.setCompanyName(ticket.getCompanyName());
        vo.setReason(ticket.getReason());
        vo.setRequestedBy(ticket.getRequestedBy());
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
            hv.setHolderUsername(h.getHolderUsername());
            hv.setHolderRealName(h.getHolderRealName());
            return hv;
        }).collect(Collectors.toList()));

        List<CaSubCaApproval> approvals = approvalMapper.selectList(new LambdaQueryWrapper<CaSubCaApproval>()
                .eq(CaSubCaApproval::getTicketId, ticket.getId())
                .orderByAsc(CaSubCaApproval::getDecidedAt));
        vo.setApprovals(approvals.stream().map(a -> {
            CaSubCaApprovalVO av = new CaSubCaApprovalVO();
            av.setApproverUsername(a.getApproverUsername());
            av.setApproverRealName(a.getApproverRealName());
            av.setDecision(a.getDecision());
            av.setComment(a.getComment());
            av.setDecidedAt(a.getDecidedAt());
            return av;
        }).collect(Collectors.toList()));

        // 当前登录人待办标记
        boolean isHolder = holders.stream().anyMatch(h -> h.getHolderUsername().equals(username));
        boolean decided = approvals.stream().anyMatch(a -> a.getApproverUsername().equals(username));
        vo.setMyPendingDecision(isHolder && !decided && TicketRebuildStatusEnum.PENDING.is(ticket.getStatus()));
        vo.setMyDecision(decided ? approvals.stream()
                .filter(a -> a.getApproverUsername().equals(username))
                .map(CaSubCaApproval::getDecision).findFirst().orElse(null) : null);
        return vo;
    }

    private KeyShareMineVO toKeyShareMineVO(CompanyKeyShare share) {
        KeyShareMineVO vo = new KeyShareMineVO();
        vo.setHolderUsername(share.getHolderUsername());
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

    private String realNameOrAccount(String realName, String username) {
        return realName != null && !realName.isBlank() ? realName : username;
    }
}
