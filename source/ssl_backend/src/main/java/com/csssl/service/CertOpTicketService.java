package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCertOpTicket;
import com.csssl.entity.CaCompany;
import com.csssl.enums.CertOpTypeEnum;
import com.csssl.enums.TicketStatusEnum;
import com.csssl.mapper.CaCertOpTicketMapper;
import com.csssl.util.CompanyScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 证书操作审批单（吊销/挂起/解除挂起/更新/重签双人复核）：
 * 发起（持 cert:revoke / cert:suspend / cert:renew / cert:rekey 权限）→ 审批（持 cert:op:approve，§10.1g 不得与发起人同一人）
 * → 批准即执行（同事务：证书状态流转进 CRL/OCSP 或吊销旧证重签新证 + 单据落终态）。
 * 状态机：PENDING → EXECUTED / REJECTED / CANCELLED（见 {@link TicketStatusEnum}）。直接执行通道已拆除，系统管理员同样只能走单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertOpTicketService {

    private static final DateTimeFormatter OP_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CaCertOpTicketMapper ticketMapper;
    private final CertService certService;
    private final AuditService auditService;
    private final CaService caService;

    /**
     * 分页查询（companyScope 非空=仅该公司；null=全量只读，由控制器按数据域收敛）
     */
    public Page<CaCertOpTicket> page(long pageNo, long pageSize, String status, String opType,
                                     String keyword, String companyScope) {
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
        qw.orderByDesc(CaCertOpTicket::getCreatedAt);
        return ticketMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /**
     * 详情（公司用户访问他公司单按不存在处理，不泄露存在性）
     */
    public CaCertOpTicket detailVisible(Long id, String operatorCompany) {
        CaCertOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("操作单不存在（id=" + id + "）");
        }
        CompanyScope.requireRowVisible(operatorCompany, ticket.getCompanyUscc());
        return ticket;
    }

    /**
     * 发起操作单（不执行，仅落 PENDING 待审批）。
     * 校验：操作类型合法、目标证书存在且在本公司域、状态与操作类型匹配、无同证书同类型待审批单、
     * 根CA/二级CA不走操作单（平台级处置）、吊销/挂起必填原因、更新/重签必填新有效期。
     */
    public CaCertOpTicket submit(String opTypeCode, Long certId, String reason, Integer validityDays,
                                 String username, String realName, String ip, String effCompany) {
        CertOpTypeEnum opType = CertOpTypeEnum.fromCode(opTypeCode);
        if (opType == null) {
            throw new IllegalArgumentException("操作类型仅支持 REVOKE-吊销 / SUSPEND-挂起 / UNSUSPEND-解除挂起 / RENEW-证书更新 / REKEY-密钥更新");
        }
        CaCert cert = certService.findById(certId);
        CompanyScope.requireRowVisible(effCompany, cert.getCompanyUscc());
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
        ticket.setRequestedBy(username);
        ticket.setRequestedByName(realNameOrAccount(realName, username));
        ticket.setRequestedAt(LocalDateTime.now());
        ticket.setCreatedAt(LocalDateTime.now());
        ticketMapper.insert(ticket);
        auditService.record(username, "CO_SUBMIT", ticket.getOpNo(),
                "发起证书操作单：" + opType.getLabel() + " " + cert.getSubjectCn()
                        + (needDays ? "（新有效期 " + validityDays + " 天）" : needReason ? "（原因：" + reason + "）" : ""),
                ip, companyOf(cert));
        log.info("[证书操作单][{}] 发起 {} id={} cn={}", ticket.getOpNo(), opType.getCode(), certId, cert.getSubjectCn());
        return ticket;
    }

    /**
     * 审批批准（不得与发起人同一人，GM/T 0034 §10.1g）。
     * 批准即执行：同事务内完成证书状态流转 + CRL/OCSP 生效 + 单据落 EXECUTED，
     * 执行前重验证书当前状态（发起后状态可能已变化）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaCertOpTicket approve(Long id, String comment, String username, String realName, String ip, String effCompany) {
        CaCertOpTicket ticket = checkPending(id);
        requireCompanyMatch(ticket.getCompanyUscc(), effCompany);
        checkReviewerDiff(ticket, username);
        CertOpTypeEnum opType = CertOpTypeEnum.fromCode(ticket.getOpType());
        if (opType == null) {
            throw new IllegalArgumentException("未知操作类型：" + ticket.getOpType());
        }
        CaCert cert = certService.findById(ticket.getCertId());
        checkStatusForOp(cert, opType);

        String operator = username;
        switch (opType) {
            case REVOKE -> certService.revoke(cert.getId(), ticket.getReason(), operator, ip);
            case SUSPEND -> certService.suspend(cert.getId(), ticket.getReason(), operator, ip);
            case UNSUSPEND -> certService.unsuspend(cert.getId(), operator, ip);
            case RENEW -> certService.renew(effCompany, cert.getId(), ticket.getValidityDays(), operator, ip);
            case REKEY -> certService.rekey(effCompany, cert.getId(), ticket.getValidityDays(), operator, ip);
        }

        ticket.setStatus(TicketStatusEnum.EXECUTED.getCode());
        ticket.setReviewedBy(username);
        ticket.setReviewedByName(realNameOrAccount(realName, username));
        ticket.setReviewedAt(LocalDateTime.now());
        ticket.setReviewComment(blankToNull(comment));
        ticket.setExecutedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        auditService.record(username, "CO_APPROVE", ticket.getOpNo(),
                "批准并执行证书操作单：" + opType.getLabel() + " " + ticket.getCertSubjectCn()
                        + "（发起人 " + ticket.getRequestedByName() + "）", ip, ticket.getCompanyUscc());
        log.info("[证书操作单][{}] 批准并执行 {} cn={} 审批人={}", ticket.getOpNo(), ticket.getOpType(),
                ticket.getCertSubjectCn(), username);
        return ticket;
    }

    /**
     * 审批驳回（不得与发起人同一人；意见必填；单据落 REJECTED，证书状态不变）
     */
    public CaCertOpTicket reject(Long id, String comment, String username, String realName, String ip, String effCompany) {
        CaCertOpTicket ticket = checkPending(id);
        requireCompanyMatch(ticket.getCompanyUscc(), effCompany);
        checkReviewerDiff(ticket, username);
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        ticket.setStatus(TicketStatusEnum.REJECTED.getCode());
        ticket.setReviewedBy(username);
        ticket.setReviewedByName(realNameOrAccount(realName, username));
        ticket.setReviewedAt(LocalDateTime.now());
        ticket.setReviewComment(comment.trim());
        ticketMapper.updateById(ticket);
        auditService.record(username, "CO_REJECT", ticket.getOpNo(),
                "驳回证书操作单：" + opText(ticket.getOpType()) + " " + ticket.getCertSubjectCn(), ip, ticket.getCompanyUscc());
        return ticket;
    }

    /**
     * 撤销操作单：仅发起人本人，且仅 PENDING 可撤销（审批人可驳回兜底，无需管理员旁路）
     */
    public CaCertOpTicket cancel(Long id, String username, String role, String ip, String effCompany) {
        CaCertOpTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("操作单不存在（id=" + id + "）");
        }
        requireCompanyMatch(ticket.getCompanyUscc(), effCompany);
        if (!Objects.equals(ticket.getRequestedBy(), username)) {
            throw new SecurityException("仅操作单发起人本人可撤销（当前操作人：" + username + "）");
        }
        if (!TicketStatusEnum.PENDING.is(ticket.getStatus())) {
            throw new IllegalArgumentException("仅「待审批」状态的操作单可撤销（当前状态：" + statusText(ticket.getStatus()) + "）");
        }
        ticket.setStatus(TicketStatusEnum.CANCELLED.getCode());
        ticketMapper.updateById(ticket);
        auditService.record(username, "CO_CANCEL", ticket.getOpNo(),
                "撤销证书操作单：" + opText(ticket.getOpType()) + " " + ticket.getCertSubjectCn(), ip, ticket.getCompanyUscc());
        return ticket;
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
        String expected = rowCompany == null || rowCompany.isBlank() ? CaCompany.PRIMARY_COMPANY_USCC : rowCompany;
        if (!expected.equals(effCompany)) {
            throw new SecurityException("权限不足：操作单归属公司 " + expected + "，与当前操作域 " + effCompany + " 不一致");
        }
    }

    /**
     * 发起与审批分离：审批人不得为发起人本人（GM/T 0034 §10.1g 同款硬要求）
     */
    private void checkReviewerDiff(CaCertOpTicket ticket, String username) {
        if (username != null && username.equals(ticket.getRequestedBy())) {
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

    private String realNameOrAccount(String realName, String username) {
        return realName != null && !realName.isBlank() ? realName : (username == null ? null : username);
    }
}
