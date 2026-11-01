package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.TenantOpSubmitDTO;
import com.csssl.entity.CaTenant;
import com.csssl.entity.CaTenantOpTicket;
import com.csssl.enums.TenantOpTypeEnum;
import com.csssl.enums.TenantStatusEnum;
import com.csssl.enums.TicketStatusEnum;
import com.csssl.mapper.CaTenantOpTicketMapper;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.TenantOpReceiptVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 租户生命周期变更审批单（暂停/恢复/关闭/重置接入密钥走单）：
 * 发起（持 tenant:op:apply）→ 审批（持 tenant:op:approve，GM/T 0034 §10.1g 不得与发起人同一人）
 * → 批准即执行（同事务完成状态流转/密钥轮换 + 单据落终态）。
 * 状态机：PENDING → EXECUTED / REJECTED / CANCELLED（见 {@link TicketStatusEnum}）。
 * 新建租户不属敏感变更，已改为直接执行（见 {@link TenantProvisionService} / POST /admin/tenants）；
 * 本服务不再接受 CREATE 新单，历史 CREATE 单据仍可查询、可领取已发放的凭证回执。
 * 接入密钥明文不落库：单据只记 App ID 与版本，由发起人凭一次性回执从密钥保险库领取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantOpTicketService {

    private static final DateTimeFormatter OP_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CaTenantOpTicketMapper ticketMapper;
    private final TenantService tenantService;
    private final ApiClientService apiClientService;
    private final AuditService auditService;

    /**
     * 分页查询（status/opType 精确、keyword 命中 单号/信用代码/租户名称）
     */
    public Page<CaTenantOpTicket> page(long pageNo, long pageSize, String status, String opType, String keyword) {
        LambdaQueryWrapper<CaTenantOpTicket> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CaTenantOpTicket::getStatus, status.trim());
        }
        if (opType != null && !opType.isBlank()) {
            qw.eq(CaTenantOpTicket::getOpType, opType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(CaTenantOpTicket::getOpNo, kw).or()
                    .like(CaTenantOpTicket::getTenantUscc, kw).or()
                    .like(CaTenantOpTicket::getTenantName, kw));
        }
        qw.orderByDesc(CaTenantOpTicket::getCreatedAt);
        return ticketMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    public CaTenantOpTicket detail(Long id) {
        CaTenantOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("变更单不存在（id=" + id + "）");
        }
        return ticket;
    }

    /**
     * 发起变更单（不执行，仅落 PENDING 待审批）。
     * 提交时先做一次参数预检（快速失败），执行时由 TenantService/ApiClientService 再验一次（以执行时为准）。
     */
    public CaTenantOpTicket submit(TenantOpSubmitDTO dto, String username, String realName, String ip) {
        TenantOpTypeEnum opType = TenantOpTypeEnum.fromCode(dto.getOpType());
        if (opType == TenantOpTypeEnum.CREATE) {
            throw new IllegalArgumentException("新建租户已改为直接执行（无需审批），请使用「新建租户」入口");
        }
        if (opType == null || !opType.isSubmittable()) {
            throw new IllegalArgumentException("变更类型仅支持 SUSPEND-暂停 / RESUME-恢复 / CLOSE-关闭 / RESET_CREDENTIAL-重置接入密钥");
        }
        String reason = blankToNull(dto.getReason());
        if (reason == null) {
            throw new IllegalArgumentException("变更事由不能为空（审批依据）");
        }
        String uscc = TenantService.normalizeUscc(dto.getUscc());
        CaTenant tenant = tenantService.get(uscc);
        checkTenantStatusForOp(tenant.getStatus(), opType);
        Long pending = ticketMapper.selectCount(new LambdaQueryWrapper<CaTenantOpTicket>()
                .eq(CaTenantOpTicket::getTenantUscc, uscc)
                .eq(CaTenantOpTicket::getOpType, opType.getCode())
                .eq(CaTenantOpTicket::getStatus, TicketStatusEnum.PENDING.getCode()));
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException("该租户已有待审批的同类变更单（" + opType.getLabel() + "），请等待审批完成或先撤回");
        }

        CaTenantOpTicket ticket = new CaTenantOpTicket();
        ticket.setOpNo("TO" + OP_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        ticket.setOpType(opType.getCode());
        ticket.setTenantUscc(uscc);
        ticket.setTenantName(tenant.getTenantName());
        ticket.setReason(reason);
        ticket.setStatus(TicketStatusEnum.PENDING.getCode());
        ticket.setRequestedBy(username);
        ticket.setRequestedByName(realNameOrAccount(realName, username));
        ticket.setRequestedAt(LocalDateTime.now());
        ticket.setCreatedAt(LocalDateTime.now());
        ticketMapper.insert(ticket);

        auditService.record(username, "TENANT_OP_SUBMIT", ticket.getOpNo(),
                "发起租户变更单：" + opType.getLabel() + " " + targetText(ticket) + "（事由：" + reason + "）",
                ip, uscc);
        log.info("[租户变更单][{}] 发起 {} 目标={} 发起人={}", ticket.getOpNo(), opType.getCode(), uscc, username);
        return ticket;
    }

    /**
     * 审批批准（不得与发起人同一人）。批准即执行：同事务完成变更并落 EXECUTED；
     * 执行前按当前租户状态重校验（发起后状态可能已变化）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaTenantOpTicket approve(Long id, String comment, String username, String realName, String ip) {
        CaTenantOpTicket ticket = checkPending(id);
        TenantOpTypeEnum opType = TenantOpTypeEnum.fromCode(ticket.getOpType());
        if (opType == null) {
            throw new IllegalArgumentException("未知变更类型：" + ticket.getOpType());
        }
        checkReviewerDiff(ticket, username);
        String reviewerName = realNameOrAccount(realName, username);
        LocalDateTime now = LocalDateTime.now();

        // 先条件占位（PENDING→EXECUTED）再执行：并发双批只有一个能成功，执行异常整事务回滚
        int reserved = ticketMapper.update(null, new LambdaUpdateWrapper<CaTenantOpTicket>()
                .eq(CaTenantOpTicket::getId, id)
                .eq(CaTenantOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .set(CaTenantOpTicket::getStatus, TicketStatusEnum.EXECUTED.getCode())
                .set(CaTenantOpTicket::getReviewedBy, username)
                .set(CaTenantOpTicket::getReviewedByName, reviewerName)
                .set(CaTenantOpTicket::getReviewedAt, now)
                .set(CaTenantOpTicket::getReviewComment, blankToNull(comment))
                .set(CaTenantOpTicket::getExecutedAt, now));
        if (reserved == 0) {
            throw new IllegalArgumentException("该变更单已被处理，请刷新后重试");
        }
        ticket.setStatus(TicketStatusEnum.EXECUTED.getCode());
        ticket.setReviewedBy(username);
        ticket.setReviewedByName(reviewerName);
        ticket.setReviewedAt(now);
        ticket.setReviewComment(blankToNull(comment));
        ticket.setExecutedAt(now);

        switch (opType) {
            case CREATE -> throw new IllegalStateException("「新建租户」已改为直接执行，无需审批；历史待审批单请驳回或撤回");
            case SUSPEND -> {
                tenantService.suspend(ticket.getTenantUscc());
                auditService.record(username, "TENANT_SUSPEND", ticket.getTenantUscc(),
                        "暂停租户 " + ticket.getTenantName() + "（变更单 " + ticket.getOpNo() + "）", ip, ticket.getTenantUscc());
            }
            case RESUME -> {
                tenantService.resume(ticket.getTenantUscc());
                auditService.record(username, "TENANT_RESUME", ticket.getTenantUscc(),
                        "恢复租户 " + ticket.getTenantName() + "（变更单 " + ticket.getOpNo() + "）", ip, ticket.getTenantUscc());
            }
            case CLOSE -> {
                tenantService.close(ticket.getTenantUscc());
                auditService.record(username, "TENANT_CLOSE", ticket.getTenantUscc(),
                        "关闭租户 " + ticket.getTenantName() + "（变更单 " + ticket.getOpNo() + "）", ip, ticket.getTenantUscc());
            }
            case RESET_CREDENTIAL -> executeResetCredential(ticket, username, ip);
        }

        ticketMapper.updateById(ticket);
        auditService.record(username, "TENANT_OP_APPROVE", ticket.getOpNo(),
                "批准并执行租户变更单：" + opType.getLabel() + " " + targetText(ticket)
                        + "（发起人 " + ticket.getRequestedByName() + "）", ip, ticket.getTenantUscc());
        log.info("[租户变更单][{}] 批准并执行 {} 目标={} 审批人={}", ticket.getOpNo(), opType.getCode(),
                ticket.getTenantUscc(), username);
        return ticket;
    }

    /**
     * 审批驳回（不得与发起人同一人；意见必填；租户状态不变）
     */
    public CaTenantOpTicket reject(Long id, String comment, String username, String realName, String ip) {
        CaTenantOpTicket ticket = checkPending(id);
        checkReviewerDiff(ticket, username);
        String remark = blankToNull(comment);
        if (remark == null) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        int updated = ticketMapper.update(null, new LambdaUpdateWrapper<CaTenantOpTicket>()
                .eq(CaTenantOpTicket::getId, id)
                .eq(CaTenantOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .set(CaTenantOpTicket::getStatus, TicketStatusEnum.REJECTED.getCode())
                .set(CaTenantOpTicket::getReviewedBy, username)
                .set(CaTenantOpTicket::getReviewedByName, realNameOrAccount(realName, username))
                .set(CaTenantOpTicket::getReviewedAt, LocalDateTime.now())
                .set(CaTenantOpTicket::getReviewComment, remark));
        if (updated == 0) {
            throw new IllegalArgumentException("该变更单已被处理，请刷新后重试");
        }
        auditService.record(username, "TENANT_OP_REJECT", ticket.getOpNo(),
                "驳回租户变更单：" + opText(ticket.getOpType()) + " " + targetText(ticket) + "（意见：" + remark + "）",
                ip, ticket.getTenantUscc());
        return detail(id);
    }

    /**
     * 撤回变更单：仅发起人本人，且仅 PENDING 可撤回（审批人可驳回兜底，无需管理员旁路）
     */
    public CaTenantOpTicket cancel(Long id, String username, String ip) {
        CaTenantOpTicket ticket = checkPending(id);
        if (!Objects.equals(ticket.getRequestedBy(), username)) {
            throw new SecurityException("仅变更单发起人本人可撤回（当前操作人：" + username + "）");
        }
        int updated = ticketMapper.update(null, new LambdaUpdateWrapper<CaTenantOpTicket>()
                .eq(CaTenantOpTicket::getId, id)
                .eq(CaTenantOpTicket::getStatus, TicketStatusEnum.PENDING.getCode())
                .set(CaTenantOpTicket::getStatus, TicketStatusEnum.CANCELLED.getCode()));
        if (updated == 0) {
            throw new IllegalArgumentException("该变更单已被处理，请刷新后重试");
        }
        auditService.record(username, "TENANT_OP_CANCEL", ticket.getOpNo(),
                "撤回租户变更单：" + opText(ticket.getOpType()) + " " + targetText(ticket), ip, ticket.getTenantUscc());
        return detail(id);
    }

    /**
     * 领取接入凭证回执（仅发起人本人、仅已执行、仅 RESET_CREDENTIAL 及历史 CREATE 单据、一次性）。
     * 密钥明文实时从保险库读取，不落单据；领取标记与密钥读取同事务，并发双领只有一个成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantOpReceiptVO takeReceipt(Long id, String username, String ip) {
        CaTenantOpTicket ticket = detail(id);
        if (!TicketStatusEnum.EXECUTED.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「已执行」的变更单可领取接入凭证（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        TenantOpTypeEnum opType = TenantOpTypeEnum.fromCode(ticket.getOpType());
        if (opType != TenantOpTypeEnum.CREATE && opType != TenantOpTypeEnum.RESET_CREDENTIAL) {
            throw new IllegalArgumentException("该类型变更单没有接入凭证回执");
        }
        if (!Objects.equals(ticket.getRequestedBy(), username)) {
            throw new SecurityException("仅变更单发起人本人可领取接入凭证回执（当前操作人：" + username + "）");
        }
        if (ticket.getReceiptTakenAt() != null) {
            throw new IllegalArgumentException("回执已领取，密钥不可再次查看；如需更换请重新发起「重置接入密钥」变更单");
        }
        if (ticket.getResultAppId() == null || ticket.getResultAppId().isBlank()) {
            throw new IllegalStateException("该变更单没有可领取的接入凭证，请重新发起变更单");
        }
        int claimed = ticketMapper.update(null, new LambdaUpdateWrapper<CaTenantOpTicket>()
                .eq(CaTenantOpTicket::getId, id)
                .isNull(CaTenantOpTicket::getReceiptTakenAt)
                .set(CaTenantOpTicket::getReceiptTakenAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new IllegalArgumentException("回执已领取，密钥不可再次查看");
        }
        String secret = apiClientService.secretOfAppId(ticket.getResultAppId());

        TenantOpReceiptVO vo = new TenantOpReceiptVO();
        vo.setOpNo(ticket.getOpNo());
        vo.setOpType(ticket.getOpType());
        vo.setTenantUscc(ticket.getTenantUscc());
        vo.setTenantName(ticket.getTenantName());
        vo.setAppId(ticket.getResultAppId());
        vo.setSecret(secret);
        vo.setSecretVersion(ticket.getResultSecretVersion());
        auditService.record(username, "TENANT_OP_RECEIPT", ticket.getOpNo(),
                "领取租户接入凭证回执：" + targetText(ticket) + "（appId=" + ticket.getResultAppId() + "，密钥仅展示一次）",
                ip, ticket.getTenantUscc());
        return vo;
    }

    /**
     * RESET_CREDENTIAL 执行：轮换/新登记默认接入凭证，旧 secret 立即失效（明文仅经发起人一次性回执交付）
     */
    private void executeResetCredential(CaTenantOpTicket ticket, String username, String ip) {
        tenantService.requireActive(ticket.getTenantUscc());
        ApiClientSecretVO credential = apiClientService.resetForTenant(ticket.getTenantUscc(), username, ip);
        ticket.setResultAppId(credential.getAppId());
        ticket.setResultSecretVersion(credential.getSecretVersion());
        auditService.record(username, "TENANT_CREDENTIAL_RESET", ticket.getTenantUscc(),
                "重置租户接入密钥：" + ticket.getTenantName() + "（appId=" + credential.getAppId()
                        + "，版本 " + credential.getSecretVersion() + "，变更单 " + ticket.getOpNo() + "）",
                ip, ticket.getTenantUscc());
    }

    /**
     * 变更类型与租户当前状态的匹配校验（发起时校验一次；执行时由 TenantService 状态机再挡一次）
     */
    private void checkTenantStatusForOp(String status, TenantOpTypeEnum opType) {
        switch (opType) {
            case SUSPEND -> {
                if (!TenantStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的租户可暂停（当前状态：" + statusText(status) + "）");
                }
            }
            case RESUME -> {
                if (!TenantStatusEnum.SUSPENDED.is(status) && !TenantStatusEnum.CLOSED.is(status)) {
                    throw new IllegalArgumentException("仅「已暂停/已关闭」状态的租户可恢复（当前状态：" + statusText(status) + "）");
                }
            }
            case CLOSE -> {
                if (TenantStatusEnum.CLOSED.is(status)) {
                    throw new IllegalArgumentException("租户已关闭，不能重复关闭");
                }
                if (!TenantStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的租户可关闭，已暂停租户请先恢复");
                }
            }
            case RESET_CREDENTIAL -> {
                if (!TenantStatusEnum.ACTIVE.is(status)) {
                    throw new IllegalArgumentException("仅「启用」状态的租户可重置接入密钥（当前状态：" + statusText(status) + "）");
                }
            }
            case CREATE -> throw new IllegalArgumentException("「新建租户」不属变更单类型");
        }
    }

    /**
     * 发起与审批分离：审批人不得为发起人本人（GM/T 0034 §10.1g 同款硬要求）
     */
    private void checkReviewerDiff(CaTenantOpTicket ticket, String username) {
        if (username != null && username.equals(ticket.getRequestedBy())) {
            throw new SecurityException("违反分权制衡：审批人不得与变更单发起人为同一人（GM/T 0034 §10.1g）");
        }
    }

    private CaTenantOpTicket checkPending(Long id) {
        CaTenantOpTicket ticket = detail(id);
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的变更单可操作（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        return ticket;
    }

    private String targetText(CaTenantOpTicket ticket) {
        String name = ticket.getTenantName() == null ? "" : " " + ticket.getTenantName();
        return ticket.getTenantUscc() + name;
    }

    private String opText(String opType) {
        TenantOpTypeEnum t = TenantOpTypeEnum.fromCode(opType);
        return t == null ? opType : t.getLabel();
    }

    private String statusText(String status) {
        TicketStatusEnum s = TicketStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String realNameOrAccount(String realName, String username) {
        return realName != null && !realName.isBlank() ? realName : username;
    }
}
