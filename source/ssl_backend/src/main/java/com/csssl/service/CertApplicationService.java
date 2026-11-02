package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.ApplicationSubmitDTO;
import com.csssl.dto.PublicKeyIssueDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.CaTenant;
import com.csssl.entity.CertApplication;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.mapper.CertApplicationMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.ApplicationTrailService;
import com.csssl.service.MessageService;
import com.csssl.util.TenantScope;
import com.csssl.vo.CertVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RA 用户注册流程（GM/T 0034 §5.2.2 用户注册管理系统）：
 * 提交（录入员）→ 审核批准/驳回（审核员，§10.1g 不得与录入员同一人）→ 签发（操作员，复用模板与公钥签发）。
 * 申请单状态机：PENDING → APPROVED / REJECTED → ISSUED / CANCELLED（见 {@link CertApplicationStatusEnum}）。
 *
 * <p>路线A数据域：提交时申请单 tenant 绑定=effectiveTenant（租户用户本人域；全局用户显式 tenantUscc，缺省 default）；
 * 列表/详情按域过滤；approve/reject/issue/cancel 校验申请单 tenant==操作者域（跨租户→403）；
 * issue 走 CertService 带 tenantUscc 正式版；租户域申请的 subjectDn 受控：CN 自由但 O 必须等于
 * 租户 subject_o（提交时校验并提示），default 域保持 O=CSSSL 老口径不校验。</p>
 */
@Service
@RequiredArgsConstructor
public class CertApplicationService {

    private static final DateTimeFormatter APPLY_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertApplicationMapper applicationMapper;
    private final CertTemplateService templateService;
    private final CertService certService;
    private final TenantService tenantService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationTrailService trailService;
    private final MessageService messageService;
    private final SysUserMapper sysUserMapper;

    /**
     * 数据域强制：申请单归属租户必须等于操作者有效域（租户用户越域 403；全局用户须显式传对 tenantUscc）
     */
    private static void requireTenantMatch(CertApplication app, String effTenant) {
        String appTenant = app.getTenantUscc() == null || app.getTenantUscc().isBlank()
                ? CaTenant.PRIMARY_TENANT_USCC : app.getTenantUscc();
        if (!appTenant.equals(effTenant)) {
            throw new SecurityException("权限不足：申请单归属租户 " + appTenant + "，与当前操作域 " + effTenant + " 不一致");
        }
    }

    /**
     * 分页查询申请单（兼容入口=跨租户全量，仅全局用户经控制器到达）
     */
    public Page<CertApplication> page(long pageNo, long pageSize, String status, String keyword) {
        return page(pageNo, pageSize, status, keyword, null);
    }

    /**
     * 分页查询申请单（租户域版：tenantScope 非空=仅该租户；null=全量只读，由控制器按数据域收敛）
     */
    public Page<CertApplication> page(long pageNo, long pageSize, String status, String keyword, String tenantScope) {
        LambdaQueryWrapper<CertApplication> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CertApplication::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CertApplication::getApplicantName, keyword).or()
                    .like(CertApplication::getApplyNo, keyword));
        }
        if (tenantScope != null && !tenantScope.isBlank()) {
            qw.eq(CertApplication::getTenantUscc, tenantScope);
        }
        qw.orderByDesc(CertApplication::getCreatedAt);
        return applicationMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /**
     * 申请单详情
     */
    public CertApplication detail(Long id) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        return app;
    }

    /**
     * 申请单详情（数据域版：租户用户访问他租户申请单按不存在处理，不泄露存在性）
     */
    public CertApplication detailVisible(Long id, String operatorTenant) {
        CertApplication app = detail(id);
        TenantScope.requireRowVisible(operatorTenant, app.getTenantUscc());
        return app;
    }

    /**
     * 提交申请（录入员代录：医生/租户提供材料，操作员录入系统）；tenant=控制器按 effectiveTenant 归一后的受理域
     */
    public CertApplication submit(ApplicationSubmitDTO body, String username, String realName, String ip, String tenant) {
        CertApplication app = create(body, username, realName, tenant);
        auditService.record(username, "RA_SUBMIT", app.getApplyNo(),
                "提交证书申请：" + app.getApplicantName() + "（" + app.getApplicantType() + "，模板 "
                        + templateCodeOf(app.getTemplateId()) + "）", ip, tenant);
        trailService.record(tenant, app.getId(), app.getApplyNo(), "SUBMIT",
                username, realNameOrAccount(realName, username), null, null, app.getStatus(), null);
        if (app.getAssigneeUsername() != null) {
            trailService.record(tenant, app.getId(), app.getApplyNo(), "ASSIGN",
                    username, realNameOrAccount(realName, username), null, null, app.getStatus(),
                    "指定审批人：" + app.getAssigneeRealName());
            messageService.send(app.getTenantUscc(), app.getAssigneeUsername(), app.getAssigneeRealName(),
                    username, "APP_SUBMITTED", "您有一条新的证书申请待审批",
                    "申请单 " + app.getApplyNo() + " 待您审批（提交人：" + app.getAppliedByName() + "）",
                    "CERT_APPLICATION", String.valueOf(app.getId()));
        }
        return app;
    }

    /**
     * 外部系统直连提交（HIS 等 API）：复用同一套校验与落库，另记外部系统/幂等键/渠道，
     * 审计主体为 API 客户端（不伪装成自然人）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertApplication submitExternal(ApplicationSubmitDTO body, String externalSystem, String externalRequestId,
                                          String requestHash, String employeeId, String employeeCode, String deptName,
                                          String username, String displayName, String ip, String tenant) {
        CertApplication app = create(body, username, displayName, tenant);
        app.setChannel("API");
        app.setExternalSystem(externalSystem);
        app.setExternalRequestId(externalRequestId);
        app.setExternalRequestHash(requestHash);
        app.setExternalEmployeeId(blankToNull(employeeId));
        app.setExternalEmployeeCode(blankToNull(employeeCode));
        app.setExternalDeptName(blankToNull(deptName));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_API_SUBMIT", app.getApplyNo(),
                "外部系统提交证书申请：" + app.getApplicantName() + "（客户端 " + displayName
                        + "，externalRequestId=" + externalRequestId + "）", ip, tenant);
        return app;
    }

    /**
     * 校验并落一条 PENDING 申请单（submit / submitExternal 共用，不写审计）
     */
    private CertApplication create(ApplicationSubmitDTO body, String username, String realName, String tenant) {
        String applicantType = body.getApplicantType();
        if (!"INSTITUTION".equals(applicantType) && !"PERSON".equals(applicantType)) {
            throw new IllegalArgumentException("申请人类别仅支持 INSTITUTION-单位 / PERSON-个人");
        }
        String subjectDn = required(body.getSubjectDn(), "subjectDn", "主体DN（如 CN=张三,O=麓康医院）");
        // 身份核验材料（身份证/营业执照）由受理租户侧留存，平台只收主体与公钥，不落证件号
        String applicantName = required(body.getApplicantName(), "applicantName", "申请人/租户名称");
        Long templateId = body.getTemplateId();
        // 模板可见集校验：受理域仅能引用全局模板或本租户模板
        CertTemplate tpl = templateService.detailVisible(templateId, tenant);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new IllegalArgumentException("SUB_CA 证书不走 RA 申请流程，请由系统管理员直接签发");
        }
        String publicKeyPem = body.getPublicKeyPem();
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥；私钥不出服务器更合规）");
        }
        // 租户域申请的主体受控校验（default 域保持 O=CSSSL 老口径，不校验）
        checkSubjectDnTenant(subjectDn, tenant);

        CertApplication app = new CertApplication();
        app.setTenantUscc(tenant);
        app.setApplyNo("RA" + APPLY_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        app.setApplicantType(applicantType);
        app.setApplicantName(applicantName.trim());
        app.setSubjectDn(subjectDn.trim());
        app.setTemplateId(templateId);
        app.setSan(blankToNull(body.getSan()));
        app.setPublicKeyPem(publicKeyPem.trim());
        app.setDescription(blankToNull(body.getDescription()));
        app.setStatus(CertApplicationStatusEnum.PENDING.getCode());
        app.setChannel("WEB");
        app.setAppliedBy(username);
        app.setAppliedByName(realNameOrAccount(realName, username));
        app.setAppliedAt(LocalDateTime.now());
        app.setCreatedAt(LocalDateTime.now());
        // 提交时指定具体审批人（可选）：仅允许本租户启用状态的 APPROVER/RA_ADMIN，且与提交人不同人
        String assignee = body.getAssigneeUsername();
        if (assignee != null && !assignee.isBlank()) {
            SysUser approver = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, assignee.trim())
                    .eq(tenant != null && !CaTenant.PRIMARY_TENANT_USCC.equals(tenant), SysUser::getTenantUscc, tenant)
                    .eq(SysUser::getStatus, 1));
            if (approver == null || !Set.of("APPROVER", "RA_ADMIN").contains(approver.getRoleCode())) {
                throw new IllegalArgumentException("指定的审批人不存在或不具审批权限（须为本租户启用状态的审核员/RA管理员）");
            }
            if (approver.getUsername().equals(username)) {
                throw new SecurityException("指定的审批人不能与提交人同一人（GM/T 0034 §10.1g 分权制衡）");
            }
            app.setAssigneeUsername(approver.getUsername());
            app.setAssigneeRealName(approver.getRealName());
        }
        applicationMapper.insert(app);
        return app;
    }

    /**
     * 模板编码（审计文案用；模板已删除时回退 ID）
     */
    private String templateCodeOf(Long templateId) {
        try {
            return templateService.detail(templateId).getTemplateCode();
        } catch (Exception e) {
            return String.valueOf(templateId);
        }
    }

    /**
     * 审核批准（审核员；不得与提交人同一人，GM/T 0034 §10.1g）。
     * 批准即固化：把模板策略快照写入申请单，签发按快照执行——批准之后模板被改动/删除
     * 都不影响已批准单实际签出的证书内容（审核人看到的即所批的）。
     * 数据域：操作者域（effTenant，effectiveTenant 归一）必须与申请单归属租户一致，跨租户 403。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertApplication approve(Long id, String comment, String username, String realName, String ip, String effTenant) {
        CertApplication app = checkPending(id);
        requireTenantMatch(app, effTenant);
        checkReviewerDiff(app, username);
        CertTemplate tpl = templateService.detailVisible(app.getTemplateId(), app.getTenantUscc());
        app.setTemplateSnapshot(snapshotJson(tpl));
        app.setStatus(CertApplicationStatusEnum.APPROVED.getCode());
        app.setReviewedBy(username);
        app.setReviewedByName(realNameOrAccount(realName, username));
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(blankToNull(comment));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_APPROVE", app.getApplyNo(),
                "批准证书申请：" + app.getApplicantName() + "（已固化模板策略快照）", ip, app.getTenantUscc());
        trailService.record(app.getTenantUscc(), app.getId(), app.getApplyNo(), "APPROVE",
                username, realNameOrAccount(realName, username), null, "PENDING", "APPROVED", blankToNull(comment));
        messageService.send(app.getTenantUscc(), app.getAppliedBy(), app.getAppliedByName(),
                username, "APP_APPROVED", "证书申请已批准",
                "您的申请单 " + app.getApplyNo() + " 已批准，等待签发", "CERT_APPLICATION", String.valueOf(app.getId()));
        return app;
    }

    /**
     * 审核驳回（审核员；不得与提交人同一人；申请单必须落在操作者域）
     */
    public CertApplication reject(Long id, String comment, String username, String realName, String ip, String effTenant) {
        CertApplication app = checkPending(id);
        requireTenantMatch(app, effTenant);
        checkReviewerDiff(app, username);
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回必须填写审核意见");
        }
        app.setStatus(CertApplicationStatusEnum.REJECTED.getCode());
        app.setReviewedBy(username);
        app.setReviewedByName(realNameOrAccount(realName, username));
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(comment.trim());
        applicationMapper.updateById(app);
        auditService.record(username, "RA_REJECT", app.getApplyNo(),
                "驳回证书申请：" + app.getApplicantName(), ip, app.getTenantUscc());
        trailService.record(app.getTenantUscc(), app.getId(), app.getApplyNo(), "REJECT",
                username, realNameOrAccount(realName, username), null, "PENDING", "REJECTED", comment.trim());
        messageService.send(app.getTenantUscc(), app.getAppliedBy(), app.getAppliedByName(),
                username, "APP_REJECTED", "证书申请被驳回",
                "您的申请单 " + app.getApplyNo() + " 被驳回" + (comment != null && !comment.isBlank() ? "：" + comment.trim() : ""),
                "CERT_APPLICATION", String.valueOf(app.getId()));
        return app;
    }

    /**
     * 签发：按批准快照 + 公钥签发，私钥由申请方自持（操作员执行）。
     * FOR UPDATE 行锁 + 同事务状态流转，防两个操作员并发对同一「已批准」单双签。
     * 数据域：申请单归属租户必须等于操作者域（跨租户 403）；签发走 CertService 带 tenantUscc 正式版，
     * 落到申请单受理租户根域。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertVO issue(Long id, String username, String ip, String effTenant) {
        CertApplication app = applicationMapper.selectForUpdate(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireTenantMatch(app, effTenant);
        if (!CertApplicationStatusEnum.APPROVED.is(app.getStatus())) {
            throw new IllegalArgumentException("仅「已批准」状态的申请单可签发（当前状态：" + statusText(app.getStatus()) + "）");
        }
        PublicKeyIssueDTO req = new PublicKeyIssueDTO();
        req.setSubjectDn(app.getSubjectDn());
        req.setPublicKeyPem(app.getPublicKeyPem());
        req.setTemplateId(app.getTemplateId());
        req.setSan(app.getSan());
        req.setDescription(app.getDescription());
        // 签发一律按快照执行，不重查可变模板最新值；存量单无快照时回退现查模板（模板须仍存在）
        CertService.IssuePolicy policy = parseSnapshot(app);
        // 租户版正式入口：落到申请单受理租户根域（requireTenantMatch 已保证 effTenant==申请单域）
        CertVO vo = certService.issueWithPublicKey(effTenant, req, policy, username, ip);

        app.setStatus(CertApplicationStatusEnum.ISSUED.getCode());
        app.setIssuedCertId(Long.valueOf(vo.getId()));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_ISSUE", app.getApplyNo(),
                "按申请签发证书：" + app.getApplicantName() + "（证书 id=" + vo.getId() + "）", ip, app.getTenantUscc());
        trailService.record(app.getTenantUscc(), app.getId(), app.getApplyNo(), "ISSUE",
                username, realNameOrAccount(null, username), null, "APPROVED", "ISSUED", null);
        messageService.send(app.getTenantUscc(), app.getAppliedBy(), app.getAppliedByName(),
                username, "APP_ISSUED", "证书已签发",
                "您的申请单 " + app.getApplyNo() + " 已签发（证书 id=" + vo.getId() + "）", "CERT_APPLICATION", String.valueOf(app.getId()));
        return vo;
    }

    /**
     * 撤销申请：仅申请人本人（录入人）可撤销，防止任一操作员越权撤销他人经办的申请单。
     * 数据域：申请单归属租户必须等于操作者域（租户用户跨租户 403；全局用户缺省域=default）。
     */
    public CertApplication cancel(Long id, String username, String role, String ip, String effTenant) {
        return cancel(id, username, role, ip, effTenant, null);
    }

    /**
     * 撤销申请（可附原因，供外部 API 记账）
     */
    public CertApplication cancel(Long id, String username, String role, String ip, String effTenant, String reason) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireTenantMatch(app, effTenant);
        if (!java.util.Objects.equals(app.getAppliedBy(), username)) {
            throw new SecurityException("仅申请提交人本人可撤销申请（当前操作人：" + username + "）");
        }
        if (!CertApplicationStatusEnum.PENDING.is(app.getStatus())
                && !CertApplicationStatusEnum.APPROVED.is(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核/已批准」状态的申请单可撤销（当前状态：" + statusText(app.getStatus()) + "）");
        }
        String fromStatus = app.getStatus();
        app.setStatus(CertApplicationStatusEnum.CANCELLED.getCode());
        applicationMapper.updateById(app);
        auditService.record(username, "RA_CANCEL", app.getApplyNo(),
                "撤销证书申请：" + app.getApplicantName()
                        + (reason == null || reason.isBlank() ? "" : "（原因：" + reason.trim() + "）"),
                ip, app.getTenantUscc());
        trailService.record(app.getTenantUscc(), app.getId(), app.getApplyNo(), "CANCEL",
                username, realNameOrAccount(null, username), role, fromStatus, "CANCELLED",
                reason == null || reason.isBlank() ? null : reason.trim());
        return app;
    }

    /**
     * 租户域申请的主体 DN 受控校验：CN 自由，但必须携带 O 且等于租户受控 subject_o（提交即校验并提示，
     * 避免签发出才被拒）；default 域保持 O=CSSSL 老口径不校验。
     */
    private void checkSubjectDnTenant(String subjectDn, String tenant) {
        if (CaTenant.LEGACY_NAMESPACE.equals(tenant)) {
            return;
        }
        CaTenant tenantRow = tenantService.get(tenant);
        X500Name name;
        try {
            name = new X500Name(subjectDn);
        } catch (Exception e) {
            throw new IllegalArgumentException("主体DN格式无效（如 CN=张三,O=" + tenantRow.getSubjectO() + "）: "
                    + e.getMessage());
        }
        String o = null;
        for (RDN rdn : name.getRDNs()) {
            for (AttributeTypeAndValue tv : rdn.getTypesAndValues()) {
                if (BCStyle.O.equals(tv.getType())) {
                    o = tv.getValue().toString();
                }
            }
        }
        if (o == null || o.isBlank()) {
            throw new IllegalArgumentException("租户域（" + tenant + "）申请的主体DN必须携带 O=" + tenantRow.getSubjectO()
                    + "（CN 可自由，O 受租户管控，防跨租户冒名）");
        }
        if (!o.equals(tenantRow.getSubjectO())) {
            throw new IllegalArgumentException("主体DN的 O（" + o + "）与受理租户受控O（" + tenantRow.getSubjectO()
                    + "）不一致，请修正后重新提交");
        }
    }

    private CertApplication checkPending(Long id) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        if (!CertApplicationStatusEnum.PENDING.is(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核」状态的申请单可审核（当前状态：" + statusText(app.getStatus()) + "）");
        }
        return app;
    }

    private String statusText(String status) {
        CertApplicationStatusEnum s = CertApplicationStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    /**
     * 录入与审核分离：审核人不得为提交人本人（GM/T 0034 §10.1g 硬要求）
     */
    private void checkReviewerDiff(CertApplication app, String username) {
        if (username != null && username.equals(app.getAppliedBy())) {
            throw new SecurityException("违反分权制衡：审核人不得与申请提交人为同一人（GM/T 0034 §10.1g）");
        }
    }

    /**
     * 批准时固化模板策略为 JSON 快照（有序字段，便于人工核对与审计比对）
     */
    private String snapshotJson(CertTemplate tpl) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("certType", tpl.getCertType());
        snapshot.put("algorithm", tpl.getAlgorithm());
        snapshot.put("validityDays", tpl.getValidityDays());
        snapshot.put("keyUsage", tpl.getKeyUsage());
        snapshot.put("extKeyUsage", tpl.getExtKeyUsage());
        snapshot.put("policyOid", tpl.getPolicyOid());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("模板策略快照序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析批准快照为签发策略；存量单快照为空时回退现查模板（模板不存在/已删除则报错，
     * 已批准但模板丢失的单不允许"凭空"签发）。
     */
    private CertService.IssuePolicy parseSnapshot(CertApplication app) {
        if (app.getTemplateSnapshot() == null || app.getTemplateSnapshot().isBlank()) {
            return snapshotOf(templateService.detailVisible(app.getTemplateId(), app.getTenantUscc()));
        }
        try {
            JsonNode node = objectMapper.readTree(app.getTemplateSnapshot());
            return new CertService.IssuePolicy(
                    text(node, "certType"),
                    text(node, "algorithm"),
                    node.hasNonNull("validityDays") ? node.get("validityDays").asInt() : null,
                    text(node, "keyUsage"),
                    text(node, "extKeyUsage"),
                    text(node, "policyOid"));
        } catch (Exception e) {
            throw new IllegalStateException("模板策略快照解析失败（申请单 " + app.getApplyNo() + "）: " + e.getMessage(), e);
        }
    }

    private CertService.IssuePolicy snapshotOf(CertTemplate tpl) {
        return new CertService.IssuePolicy(tpl.getCertType(), tpl.getAlgorithm(), tpl.getValidityDays(),
                tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid());
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String required(String v, String key, String label) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 不能为空（" + label + "）");
        }
        return v.trim();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 优先用令牌中的真实姓名；为空（旧令牌未带姓名）时回退登录账号，保证展示不为空
     */
    private String realNameOrAccount(String realName, String username) {
        return realName != null && !realName.isBlank() ? realName : (username == null ? null : username);
    }

    /**
     * 申请单审批轨迹（按步时间正序；先校验存在 + 数据域）
     */
    public List<ApplicationTrail> listTrail(Long id) {
        detail(id);
        return trailService.listByApplication(id);
    }

    /**
     * 催办：向被指派审批人推送站内信（无指派时通知本租户全部 APPROVER/RA_ADMIN），并补 URGE 轨迹 + 审计。
     * 仅提交人（及 RA 业务管理员）可催办，且申请单须处于「待审核」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void urge(Long id, LoginUser operator, String ip) {
        String effTenant = operator.getTenantUscc() == null || operator.getTenantUscc().isBlank()
                ? CaTenant.PRIMARY_TENANT_USCC : operator.getTenantUscc();
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireTenantMatch(app, effTenant);
        if (!CertApplicationStatusEnum.PENDING.is(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核」状态的申请单可催办（当前状态：" + statusText(app.getStatus()) + "）");
        }
        String recipientUsername;
        String recipientRealName;
        if (app.getAssigneeUsername() != null && !app.getAssigneeUsername().isBlank()) {
            recipientUsername = app.getAssigneeUsername();
            recipientRealName = app.getAssigneeRealName();
        } else {
            // 未指定审批人：通知本租户全部启用状态的审核员/RA管理员
            SysUser approver = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getRoleCode, "APPROVER", "RA_ADMIN")
                    .eq(effTenant != null && !CaTenant.PRIMARY_TENANT_USCC.equals(effTenant), SysUser::getTenantUscc, effTenant)
                    .eq(SysUser::getStatus, 1)
                    .last("LIMIT 1"));
            if (approver == null) {
                throw new IllegalStateException("本租户暂无启用状态的审核员/RA管理员，无法催办");
            }
            recipientUsername = approver.getUsername();
            recipientRealName = approver.getRealName();
        }
        messageService.send(app.getTenantUscc(), recipientUsername, recipientRealName,
                operator.getUsername(), "APP_URGE", "证书申请待审批催办",
                "申请单 " + app.getApplyNo() + " 待审批催办（提交人：" + app.getAppliedByName() + "）",
                "CERT_APPLICATION", String.valueOf(app.getId()));
        trailService.record(app.getTenantUscc(), app.getId(), app.getApplyNo(), "URGE",
                operator.getUsername(), realNameOrAccount(operator.getRealName(), operator.getUsername()),
                operator.getRoleCode(), "PENDING", "PENDING", "催办审批人：" + recipientRealName);
        auditService.record(operator.getUsername(), "RA_URGE", app.getApplyNo(),
                "催办审批人 " + recipientRealName + " 处理申请单", ip, app.getTenantUscc());
    }
}
