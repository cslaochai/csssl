package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.ApplicationSubmitDTO;
import com.csssl.dto.PublicKeyIssueDTO;
import com.csssl.entity.*;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.mapper.CertApplicationMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.util.PermissionCatalog;
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
import java.util.*;

/**
 * RA 用户注册流程（GM/T 0034 §5.2.2 用户注册管理系统）：
 * 提交（录入员）→ 审核批准/驳回（审核员，§10.1g 不得与录入员同一人）→ 签发（操作员，复用模板与公钥签发）。
 * 申请单状态机：PENDING → APPROVED / REJECTED → ISSUED / CANCELLED（见 {@link CertApplicationStatusEnum}）。
 *
 * <p>路线A数据域：提交时申请单 company 绑定=effectiveCompany（公司用户本人域；全局用户显式 companyUscc，缺省 default）；
 * 列表/详情按域过滤；approve/reject/issue/cancel 校验申请单 company==操作者域（跨公司→403）；
 * issue 走 CertService 带 companyUscc 正式版；公司域申请的 subjectDn 受控：CN 自由但 O 必须等于
 * 公司 subject_o（提交时校验并提示），default 域保持 O=CSSSL 老口径不校验。</p>
 *
 * <p>单必归属到人：提交时必须指派具体审批人（对外 API 通道由系统按积压最少自动分派），
 * 列表按角色可见范围收口、批准/驳回校验登录人==被指派人，详见
 * {@link #applyViewerScope} 与 {@link #checkAssignee}。</p>
 */
@Service
@RequiredArgsConstructor
public class CertApplicationService {

    private static final DateTimeFormatter APPLY_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertApplicationMapper applicationMapper;
    private final CertTemplateService templateService;
    private final CertService certService;
    private final CompanyService companyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationTrailService trailService;
    private final MessageService messageService;
    private final SysUserMapper sysUserMapper;

    /**
     * 数据域强制：申请单归属公司必须等于操作者有效域（公司用户越域 403；全局用户须显式传对 companyUscc）
     */
    private static void requireCompanyMatch(CertApplication app, String effCompany) {
        String appCompany = app.getCompanyUscc() == null || app.getCompanyUscc().isBlank()
                ? CaCompany.PRIMARY_COMPANY_USCC : app.getCompanyUscc();
        if (!appCompany.equals(effCompany)) {
            throw new SecurityException("权限不足：申请单归属公司 " + appCompany + "，与当前操作域 " + effCompany + " 不一致");
        }
    }

    /**
     * 「无人认领」的待审单：未指派（历史单），或指派人账号已停用/已被删——
     * 审批人离职不能把单永久卡死，这类单回落到审核员池共同处理。
     */
    private static final String UNCLAIMED_ASSIGNEE_SQL =
            "(assignee_user_id is null or assignee_user_id not in"
                    + " (select id from ssl_sys_user where status = 1))";

    /**
     * 分页查询申请单（数据域 + 角色可见范围双重重收口，服务端强制，不接受前端传审核人参数）
     */
    public Page<CertApplication> page(long pageNo, long pageSize, String status, String keyword,
                                      String companyScope, LoginUser viewer) {
        LambdaQueryWrapper<CertApplication> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CertApplication::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CertApplication::getApplicantName, keyword).or()
                    .like(CertApplication::getApplyNo, keyword));
        }
        if (companyScope != null && !companyScope.isBlank()) {
            qw.eq(CertApplication::getCompanyUscc, companyScope);
        }
        applyViewerScope(qw, viewer);
        qw.orderByDesc(CertApplication::getCreatedAt);
        Page<CertApplication> page = applicationMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        markReviewable(page.getRecords(), viewer);
        return page;
    }

    /**
     * 角色可见范围：持有审核/录入/签发权限点的人收窄到「与我相关 + 我能处理」，
     * RA业务管理员（流程监管、可撤销）与纯只读巡检角色（ADMIN/审计员，无上述权限点）保持数据域全量。
     *
     * <p>可见不等于可批——批准/驳回另有 {@link #checkAssignee} 硬校验，
     * 收口口径由 {@link #markReviewable} 回传给前端决定按钮显隐。</p>
     */
    private void applyViewerScope(LambdaQueryWrapper<CertApplication> qw, LoginUser viewer) {
        List<String> perms = viewer.getPermissions();
        boolean canApprove = perms.contains(PermissionCatalog.APP_APPROVE);
        boolean canSubmit = perms.contains(PermissionCatalog.APP_SUBMIT);
        boolean canIssue = perms.contains(PermissionCatalog.APP_ISSUE);
        if ((!canApprove && !canSubmit && !canIssue) || "RA_ADMIN".equals(viewer.getRoleCode())) {
            return;
        }
        Long me = viewer.getUserId();
        String pending = CertApplicationStatusEnum.PENDING.getCode();
        String approved = CertApplicationStatusEnum.APPROVED.getCode();
        qw.and(w -> {
            w.eq(CertApplication::getAppliedByUserId, me)
                    .or().eq(CertApplication::getAssigneeUserId, me)
                    .or().eq(CertApplication::getReviewedByUserId, me);
            if (canApprove) {
                w.or(x -> x.eq(CertApplication::getStatus, pending).apply(UNCLAIMED_ASSIGNEE_SQL));
            }
            if (canIssue) {
                w.or().eq(CertApplication::getStatus, approved);
            }
        });
    }

    /**
     * 逐行标注「当前登录人可否审核」，与 {@link #checkAssignee} 同一口径，前端据此隐藏按钮（不置灰）。
     */
    private void markReviewable(List<CertApplication> rows, LoginUser viewer) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean canReview = viewer.getPermissions().contains(PermissionCatalog.APP_APPROVE)
                || viewer.getPermissions().contains(PermissionCatalog.APP_REJECT);
        Long me = viewer.getUserId();
        Set<Long> enabled = canReview ? enabledUserIds() : Set.of();
        String pending = CertApplicationStatusEnum.PENDING.getCode();
        for (CertApplication row : rows) {
            row.setReviewable(canReview && pending.equals(row.getStatus())
                    && isAssigneeTakable(row.getAssigneeUserId(), me, enabled));
            row.setUrgeable(pending.equals(row.getStatus()) && row.getAssigneeUserId() != null);
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

    /**
     * 全部启用状态账号 id（审批人池规模很小，一次取回避免逐行查库）
     */
    private Set<Long> enabledUserIds() {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .eq(SysUser::getStatus, 1));
        Set<Long> ids = new HashSet<>();
        users.forEach(u -> ids.add(u.getId()));
        return ids;
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
     * 申请单详情（数据域版：公司用户访问他公司申请单按不存在处理，不泄露存在性）
     *
     * <p>与列表同一口径逐行回算 {@code reviewable/urgeable}：登录账号已不再随出参下发，
     * 详情页的按钮显隐只能靠这两个标志。</p>
     */
    public CertApplication detailVisible(Long id, LoginUser viewer) {
        CertApplication app = detail(id);

        markReviewable(List.of(app), viewer);
        return app;
    }

    /**
     * 提交申请（录入员代录：医生/公司提供材料，操作员录入系统）；company=控制器按 effectiveCompany 归一后的受理域
     */
    public CertApplication submit(ApplicationSubmitDTO body, LoginUser actor, String ip, String company) {
        CertApplication app = create(body, actor, company);
        auditService.record(actor, "RA_SUBMIT", app.getApplyNo(),
                "提交证书申请：" + app.getApplicantName() + "（" + app.getApplicantType() + "，模板 "
                        + templateCodeOf(app.getTemplateId()) + "）", ip, company);
        trailService.record(company, app.getId(), app.getApplyNo(), "SUBMIT",
                actor, null, app.getStatus(), null);
        trailService.record(company, app.getId(), app.getApplyNo(), "ASSIGN",
                actor, null, app.getStatus(),
                "指定审批人：" + app.getAssigneeRealName());
        messageService.send(app.getCompanyUscc(), app.getAssigneeUserId(), app.getAssigneeRealName(),
                actor, "APP_SUBMITTED", "您有一条新的证书申请待审批",
                "申请单 " + app.getApplyNo() + " 待您审批（提交人：" + app.getAppliedByName() + "）",
                "CERT_APPLICATION", String.valueOf(app.getId()));
        return app;
    }

    /**
     * 外部系统直连提交（HIS 等 API）：HIS 侧没有人可挑审批人，按「当前待审积压最少」自动指派，
     * 与 WEB 通道共用同一套「单必归属到人」口径；其余复用同一套校验与落库，
     * 另记外部系统/幂等键/渠道，审计主体为 API 客户端（不伪装成自然人）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertApplication submitExternal(ApplicationSubmitDTO body, String externalSystem, String externalRequestId,
                                          String requestHash, String employeeId, String employeeCode, String deptName,
                                          String username, String displayName, String ip, String company) {
        body.setAssigneeUsername(leastPendingApprover().getUsername());
        Long userId = null;
        if (username != null && !username.isBlank()) {
            SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username.trim()));
            userId = user == null ? null : user.getId();
        }
        CertApplication app = createAs(body, userId, displayName, company);
        app.setChannel("API");
        app.setExternalSystem(externalSystem);
        app.setExternalRequestId(externalRequestId);
        app.setExternalRequestHash(requestHash);
        app.setExternalEmployeeId(blankToNull(employeeId));
        app.setExternalEmployeeCode(blankToNull(employeeCode));
        app.setExternalDeptName(blankToNull(deptName));
        applicationMapper.updateById(app);
        auditService.recordAs(userId, displayName, "RA_API_SUBMIT", app.getApplyNo(),
                "外部系统提交证书申请：" + app.getApplicantName() + "（客户端 " + displayName
                        + "，externalRequestId=" + externalRequestId + "）", ip, company);
        return app;
    }

    /**
     * 校验并落一条 PENDING 申请单（WEB 提交通道，登录人版）
     */
    private CertApplication create(ApplicationSubmitDTO body, LoginUser loginUser, String company) {
        String applicantType = body.getApplicantType();
        if (!"INSTITUTION".equals(applicantType) && !"PERSON".equals(applicantType)) {
            throw new IllegalArgumentException("申请人类别仅支持 INSTITUTION-单位 / PERSON-个人");
        }
        String subjectDn = required(body.getSubjectDn(), "subjectDn", "主体DN（如 CN=张三,O=麓康医院）");
        // 身份核验材料（身份证/营业执照）由受理公司侧留存，平台只收主体与公钥，不落证件号
        String applicantName = required(body.getApplicantName(), "applicantName", "申请人/公司名称");
        Long templateId = body.getTemplateId();
        // 模板可见集校验：受理域仅能引用全局模板或本公司模板
        CertTemplate tpl = templateService.detailVisible(templateId, company);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new IllegalArgumentException("二级CA 不走 RA 申请流程：请发起「二级CA重建申请」，由 5 名密钥保管人审批后用根CA 重新签发");
        }
        String publicKeyPem = body.getPublicKeyPem();
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥；私钥不出服务器更合规）");
        }
        // 公司域申请的主体受控校验（default 域保持 O=CSSSL 老口径，不校验）
        checkSubjectDnCompany(subjectDn, company);

        CertApplication app = new CertApplication();
        app.setCompanyUscc(company);
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
        app.setAppliedByUserId(loginUser == null ? null : loginUser.getUserId());
        app.setAppliedByName(loginUser == null ? null : loginUser.getRealName());
        app.setAppliedAt(LocalDateTime.now());
        app.setCreatedAt(LocalDateTime.now());
        // 单必归属到人：提交时必须指派具体审批人，审核列表与审批接口都据此收口。
        // 公司级统管，任何启用状态的 APPROVER/RA_ADMIN 均可指派，且与提交人不同人。
        String assignee = body.getAssigneeUsername();
        if (assignee == null || assignee.isBlank()) {
            throw new IllegalArgumentException("必须指定审批人（本单只能由被指派的审批人审核）");
        }
        SysUser approver = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, assignee.trim())
                .eq(SysUser::getStatus, 1));
        if (approver == null || !Set.of("APPROVER", "RA_ADMIN").contains(approver.getRoleCode())) {
            throw new IllegalArgumentException("指定的审批人不存在或不具审批权限（须为公司启用状态的审核员/RA管理员）");
        }
        if (approver.getId().equals(loginUser == null ? null : loginUser.getUserId())) {
            throw new SecurityException("指定的审批人不能与提交人同一人（GM/T 0034 §10.1g 分权制衡）");
        }
        app.setAssigneeUserId(approver.getId());
        app.setAssigneeRealName(approver.getRealName());
        applicationMapper.insert(app);
        return app;
    }

    /**
     * 校验并落一条 PENDING 申请单（外部 API 通道，无登录态，指定 user_id + 姓名快照版）
     */
    private CertApplication createAs(ApplicationSubmitDTO body, Long userId, String realName, String company) {
        String applicantType = body.getApplicantType();
        if (!"INSTITUTION".equals(applicantType) && !"PERSON".equals(applicantType)) {
            throw new IllegalArgumentException("申请人类别仅支持 INSTITUTION-单位 / PERSON-个人");
        }
        String subjectDn = required(body.getSubjectDn(), "subjectDn", "主体DN（如 CN=张三,O=麓康医院）");
        String applicantName = required(body.getApplicantName(), "applicantName", "申请人/公司名称");
        Long templateId = body.getTemplateId();
        CertTemplate tpl = templateService.detailVisible(templateId, company);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new IllegalArgumentException("二级CA 不走 RA 申请流程：请发起「二级CA重建申请」，由 5 名密钥保管人审批后用根CA 重新签发");
        }
        String publicKeyPem = body.getPublicKeyPem();
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥；私钥不出服务器更合规）");
        }
        checkSubjectDnCompany(subjectDn, company);

        CertApplication app = new CertApplication();
        app.setCompanyUscc(company);
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
        app.setAppliedByUserId(userId);
        app.setAppliedByName(realName);
        app.setAppliedAt(LocalDateTime.now());
        app.setCreatedAt(LocalDateTime.now());
        String assignee = body.getAssigneeUsername();
        SysUser approver = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, assignee.trim())
                .eq(SysUser::getStatus, 1));
        app.setAssigneeUserId(approver.getId());
        app.setAssigneeRealName(approver.getRealName());
        applicationMapper.insert(app);
        return app;
    }

    /**
     * 自动分派（对外 API 通道用）：取启用状态审批人里当前待审积压最少的一个，
     * 同积压按账号升序取首个，保证同一时刻分派结果可复现。
     * 审批人池与 /approvers 名单同口径（公司级统管，不按公司过滤）。
     */
    private SysUser leastPendingApprover() {
        List<SysUser> pool = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .in(SysUser::getRoleCode, "APPROVER", "RA_ADMIN")
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getUsername));
        if (pool.isEmpty()) {
            throw new IllegalStateException("当前无启用状态的审核员/RA管理员，无法受理证书申请");
        }
        Map<Long, Long> backlog = new HashMap<>();
        QueryWrapper<CertApplication> group = new QueryWrapper<>();
        group.select("assignee_user_id as assignee", "count(1) as cnt")
                .eq("status", CertApplicationStatusEnum.PENDING.getCode())
                .in("assignee_user_id", pool.stream().map(SysUser::getId).toList())
                .groupBy("assignee_user_id");
        for (Map<String, Object> row : applicationMapper.selectMaps(group)) {
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
     * 审核批准（审核员；不得与提交人同一人，GM/T 0034 §10.1g；且须为本单被指派的审批人）。
     * 批准即固化：把模板策略快照写入申请单，签发按快照执行——批准之后模板被改动/删除
     * 都不影响已批准单实际签出的证书内容（审核人看到的即所批的）。
     * 数据域：操作者域（effCompany，effectiveCompany 归一）必须与申请单归属公司一致，跨公司 403。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertApplication approve(Long id, String comment, LoginUser loginUser, String ip, String effCompany) {
        CertApplication app = checkPending(id);
        requireCompanyMatch(app, effCompany);
        checkReviewerDiff(app, loginUser);
        checkAssignee(app, loginUser);
        CertTemplate tpl = templateService.detailVisible(app.getTemplateId(), app.getCompanyUscc());
        app.setTemplateSnapshot(snapshotJson(tpl));
        app.setStatus(CertApplicationStatusEnum.APPROVED.getCode());
        app.setReviewedByUserId(loginUser == null ? null : loginUser.getUserId());
        app.setReviewedByName(loginUser == null ? null : loginUser.getRealName());
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(blankToNull(comment));
        applicationMapper.updateById(app);
        auditService.record(loginUser, "RA_APPROVE", app.getApplyNo(),
                "批准证书申请：" + app.getApplicantName() + "（已固化模板策略快照）", ip, app.getCompanyUscc());
        trailService.record(app.getCompanyUscc(), app.getId(), app.getApplyNo(), "APPROVE",
                loginUser, "PENDING", "APPROVED", blankToNull(comment));
        messageService.send(app.getCompanyUscc(), app.getAppliedByUserId(), app.getAppliedByName(),
                loginUser, "APP_APPROVED", "证书申请已批准",
                "您的申请单 " + app.getApplyNo() + " 已批准，等待签发", "CERT_APPLICATION", String.valueOf(app.getId()));
        return app;
    }

    /**
     * 审核驳回（审核员；不得与提交人同一人；申请单必须落在操作者域）
     */
    public CertApplication reject(Long id, String comment, LoginUser loginUser, String ip, String effCompany) {
        CertApplication app = checkPending(id);
        requireCompanyMatch(app, effCompany);
        checkReviewerDiff(app, loginUser);
        checkAssignee(app, loginUser);
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回必须填写审核意见");
        }
        app.setStatus(CertApplicationStatusEnum.REJECTED.getCode());
        app.setReviewedByUserId(loginUser == null ? null : loginUser.getUserId());
        app.setReviewedByName(loginUser == null ? null : loginUser.getRealName());
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(comment.trim());
        applicationMapper.updateById(app);
        auditService.record(loginUser, "RA_REJECT", app.getApplyNo(),
                "驳回证书申请：" + app.getApplicantName(), ip, app.getCompanyUscc());
        trailService.record(app.getCompanyUscc(), app.getId(), app.getApplyNo(), "REJECT",
                loginUser, "PENDING", "REJECTED", comment.trim());
        messageService.send(app.getCompanyUscc(), app.getAppliedByUserId(), app.getAppliedByName(),
                loginUser, "APP_REJECTED", "证书申请被驳回",
                "您的申请单 " + app.getApplyNo() + " 被驳回" + (comment != null && !comment.isBlank() ? "：" + comment.trim() : ""),
                "CERT_APPLICATION", String.valueOf(app.getId()));
        return app;
    }

    /**
     * 签发：按批准快照 + 公钥签发，私钥由申请方自持（操作员执行）。
     * FOR UPDATE 行锁 + 同事务状态流转，防两个操作员并发对同一「已批准」单双签。
     * 数据域：申请单归属公司必须等于操作者域（跨公司 403）；签发走 CertService 带 companyUscc 正式版，
     * 落到申请单受理公司根域。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertVO issue(Long id, LoginUser actor, String ip, String effCompany) {
        CertApplication app = applicationMapper.selectForUpdate(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireCompanyMatch(app, effCompany);
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
        // 公司版正式入口：落到申请单受理公司根域（requireCompanyMatch 已保证 effCompany==申请单域）
        CertVO vo = certService.issueWithPublicKey(effCompany, req, policy, actor, ip);

        app.setStatus(CertApplicationStatusEnum.ISSUED.getCode());
        app.setIssuedCertId(Long.valueOf(vo.getId()));
        applicationMapper.updateById(app);
        auditService.record(actor, "RA_ISSUE", app.getApplyNo(),
                "按申请签发证书：" + app.getApplicantName() + "（证书 id=" + vo.getId() + "）", ip, app.getCompanyUscc());
        trailService.record(app.getCompanyUscc(), app.getId(), app.getApplyNo(), "ISSUE",
                actor, "APPROVED", "ISSUED", null);
        messageService.send(app.getCompanyUscc(), app.getAppliedByUserId(), app.getAppliedByName(),
                actor, "APP_ISSUED", "证书已签发",
                "您的申请单 " + app.getApplyNo() + " 已签发（证书 id=" + vo.getId() + "）", "CERT_APPLICATION", String.valueOf(app.getId()));
        return vo;
    }

    /**
     * 撤销申请：仅申请人本人（录入人）可撤销，防止任一操作员越权撤销他人经办的申请单。
     * 数据域：申请单归属公司必须等于操作者域（公司用户跨公司 403；全局用户缺省域=default）。
     */
    public CertApplication cancel(Long id, LoginUser loginUser, String ip, String effCompany) {
        return cancel(id, loginUser, ip, effCompany, null);
    }

    /**
     * 撤销申请（可附原因，供外部 API 记账）
     */
    public CertApplication cancel(Long id, LoginUser loginUser, String ip, String effCompany, String reason) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireCompanyMatch(app, effCompany);
        if (!Objects.equals(app.getAppliedByUserId(), loginUser == null ? null : loginUser.getUserId())) {
            throw new SecurityException("仅申请提交人本人可撤销申请");
        }
        if (!CertApplicationStatusEnum.PENDING.is(app.getStatus())
                && !CertApplicationStatusEnum.APPROVED.is(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核/已批准」状态的申请单可撤销（当前状态：" + statusText(app.getStatus()) + "）");
        }
        String fromStatus = app.getStatus();
        app.setStatus(CertApplicationStatusEnum.CANCELLED.getCode());
        applicationMapper.updateById(app);
        auditService.record(loginUser, "RA_CANCEL", app.getApplyNo(),
                "撤销证书申请：" + app.getApplicantName()
                        + (reason == null || reason.isBlank() ? "" : "（原因：" + reason.trim() + "）"),
                ip, app.getCompanyUscc());
        trailService.record(app.getCompanyUscc(), app.getId(), app.getApplyNo(), "CANCEL",
                loginUser, fromStatus, "CANCELLED",
                reason == null || reason.isBlank() ? null : reason.trim());
        return app;
    }

    /**
     * 公司域申请的主体 DN 受控校验：CN 自由，但必须携带 O 且等于公司受控 subject_o（提交即校验并提示，
     * 避免签发出才被拒）；default 域保持 O=CSSSL 老口径不校验。
     */
    private void checkSubjectDnCompany(String subjectDn, String company) {
        if (CaCompany.LEGACY_NAMESPACE.equals(company)) {
            return;
        }
        CaCompany companyRow = companyService.get(company);
        X500Name name;
        try {
            name = new X500Name(subjectDn);
        } catch (Exception e) {
            throw new IllegalArgumentException("主体DN格式无效（如 CN=张三,O=" + companyRow.getSubjectO() + "）: "
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
            throw new IllegalArgumentException("公司域（" + company + "）申请的主体DN必须携带 O=" + companyRow.getSubjectO()
                    + "（CN 可自由，O 受公司管控，防跨公司冒名）");
        }
        if (!o.equals(companyRow.getSubjectO())) {
            throw new IllegalArgumentException("主体DN的 O（" + o + "）与受理公司受控O（" + companyRow.getSubjectO()
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
    private void checkReviewerDiff(CertApplication app, LoginUser loginUser) {
        Long reviewerId = loginUser == null ? null : loginUser.getUserId();
        if (reviewerId != null && reviewerId.equals(app.getAppliedByUserId())) {
            throw new SecurityException("违反分权制衡：审核人不得与申请提交人为同一人（GM/T 0034 §10.1g）");
        }
    }

    /**
     * 审批准入：本单只能由被指派的审批人处理。未指派（历史单）或指派人已停用/已被删时，
     * 回落到审核员池共同处理——与列表可见范围 {@link #applyViewerScope} 同一口径。
     */
    private void checkAssignee(CertApplication app, LoginUser loginUser) {
        Long assignee = app.getAssigneeUserId();
        if (assignee == null || assignee.equals(loginUser == null ? null : loginUser.getUserId())) {
            return;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, assignee)
                .eq(SysUser::getStatus, 1));
        if (user == null) {
            return;
        }
        throw new SecurityException("权限不足：本单已指派给 " + app.getAssigneeRealName()
                + " 审批，当前登录账号不是被指派的审批人");
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
            return snapshotOf(templateService.detailVisible(app.getTemplateId(), app.getCompanyUscc()));
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
     * 申请单审批轨迹（按步时间正序；先校验存在 + 数据域）
     */
    public List<ApplicationTrail> listTrail(Long id) {
        detail(id);
        return trailService.listByApplication(id);
    }

    /**
     * 催办：向被指派审批人推送站内信（无指派时通知本公司全部 APPROVER/RA_ADMIN），并补 URGE 轨迹 + 审计。
     * 仅提交人（及 RA 业务管理员）可催办，且申请单须处于「待审核」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void urge(Long id, LoginUser operator, String ip) {
        String effCompany = CaCompany.PRIMARY_COMPANY_USCC;
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireCompanyMatch(app, effCompany);
        if (!CertApplicationStatusEnum.PENDING.is(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核」状态的申请单可催办（当前状态：" + statusText(app.getStatus()) + "）");
        }
        Long recipientUserId;
        String recipientRealName;
        if (app.getAssigneeUserId() != null) {
            recipientUserId = app.getAssigneeUserId();
            recipientRealName = app.getAssigneeRealName();
        } else {
            // 未指定审批人：通知公司级全部启用状态的审核员/RA管理员
            SysUser approver = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getRoleCode, "APPROVER", "RA_ADMIN")
                    .eq(SysUser::getStatus, 1)
                    .last("LIMIT 1"));
            if (approver == null) {
                throw new IllegalStateException("当前暂无启用状态的审核员/RA管理员，无法催办");
            }
            recipientUserId = approver.getId();
            recipientRealName = approver.getRealName();
        }
        messageService.send(app.getCompanyUscc(), recipientUserId, recipientRealName,
                operator, "APP_URGE", "证书申请待审批催办",
                "申请单 " + app.getApplyNo() + " 待审批催办（提交人：" + app.getAppliedByName() + "）",
                "CERT_APPLICATION", String.valueOf(app.getId()));
        trailService.record(app.getCompanyUscc(), app.getId(), app.getApplyNo(), "URGE",
                operator, "PENDING", "PENDING", "催办审批人：" + recipientRealName);
        auditService.record(operator, "RA_URGE", app.getApplyNo(),
                "催办审批人 " + recipientRealName + " 处理申请单", ip, app.getCompanyUscc());
    }
}
