package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.PublicKeyIssueRequest;
import com.csssl.dto.CertVo;
import com.csssl.entity.CaOrg;
import com.csssl.entity.CertApplication;
import com.csssl.entity.CertTemplate;
import com.csssl.mapper.CertApplicationMapper;
import com.csssl.util.RoleCode;
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
import java.util.Map;

/**
 * RA 用户注册流程（GM/T 0034 §5.2.2 用户注册管理系统）：
 * 提交（录入员）→ 审核批准/驳回（审核员，§10.1g 不得与录入员同一人）→ 签发（操作员，复用模板与公钥签发）。
 * 申请单状态机：PENDING → APPROVED / REJECTED → ISSUED / CANCELLED。
 *
 * <p>路线A数据域：提交时申请单 org 绑定=effectiveOrg（机构用户本人域；全局用户显式 orgKey，缺省 default）；
 * 列表/详情按域过滤；approve/reject/issue/cancel 校验申请单 org==操作者域（跨机构→403）；
 * issue 走 CertService 带 orgKey 正式版；机构域申请的 subjectDn 受控：CN 自由但 O 必须等于
 * 机构 subject_o（提交时校验并提示），default 域保持 O=CSSSL 老口径不校验。</p>
 */
@Service
@RequiredArgsConstructor
public class CertApplicationService {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_ISSUED = "ISSUED";
    public static final String ST_CANCELLED = "CANCELLED";

    private static final DateTimeFormatter APPLY_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertApplicationMapper applicationMapper;
    private final CertTemplateService templateService;
    private final CertService certService;
    private final OrgService orgService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 分页查询申请单（兼容入口=跨机构全量，仅全局用户经控制器到达） */
    public Page<CertApplication> page(long pageNo, long pageSize, String status, String keyword) {
        return page(pageNo, pageSize, status, keyword, null);
    }

    /** 分页查询申请单（机构域版：orgScope 非空=仅该机构；null=全量只读，由控制器按数据域收敛） */
    public Page<CertApplication> page(long pageNo, long pageSize, String status, String keyword, String orgScope) {
        LambdaQueryWrapper<CertApplication> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CertApplication::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CertApplication::getApplicantName, keyword).or()
                    .like(CertApplication::getApplyNo, keyword));
        }
        if (orgScope != null && !orgScope.isBlank()) {
            qw.eq(CertApplication::getOrgKey, orgScope);
        }
        qw.orderByDesc(CertApplication::getCreatedAt);
        return applicationMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 申请单详情 */
    public CertApplication detail(Long id) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        return app;
    }

    /** 申请单详情（数据域版：机构用户访问他机构申请单按不存在处理，不泄露存在性） */
    public CertApplication detailVisible(Long id, String operatorOrg) {
        CertApplication app = detail(id);
        RoleCode.requireRowVisible(operatorOrg, app.getOrgKey());
        return app;
    }

    /** 提交申请（录入员代录：医生/机构提供材料，操作员录入系统）；org=控制器按 effectiveOrg 归一后的受理域 */
    public CertApplication submit(Map<String, String> body, String username, String ip, String org) {
        String applicantType = body.get("applicantType");
        if (!"INSTITUTION".equals(applicantType) && !"PERSON".equals(applicantType)) {
            throw new IllegalArgumentException("申请人类别仅支持 INSTITUTION-机构 / PERSON-个人");
        }
        String subjectDn = required(body, "subjectDn", "主体DN（如 CN=张三,O=麓康医院,OU=门诊部）");
        // 身份核验材料（身份证/营业执照）由受理机构侧留存，平台只收主体与公钥，不落证件号
        String applicantName = required(body, "applicantName", "申请人/机构名称");
        Long templateId = Long.parseLong(required(body, "templateId", "证书模板ID"));
        // 模板可见集校验：受理域仅能引用全局模板或本机构模板
        CertTemplate tpl = templateService.detailVisible(templateId, org);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new IllegalArgumentException("SUB_CA 证书不走 RA 申请流程，请由系统管理员直接签发");
        }
        String publicKeyPem = body.get("publicKeyPem");
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥；私钥不出服务器更合规）");
        }
        // 机构域申请的主体受控校验（default 域保持 O=CSSSL 老口径，不校验）
        checkSubjectDnOrg(subjectDn, org);

        CertApplication app = new CertApplication();
        app.setOrgKey(org);
        app.setApplyNo("RA" + APPLY_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        app.setApplicantType(applicantType);
        app.setApplicantName(applicantName.trim());
        app.setSubjectDn(subjectDn.trim());
        app.setTemplateId(templateId);
        app.setSan(blankToNull(body.get("san")));
        app.setPublicKeyPem(publicKeyPem.trim());
        app.setDescription(blankToNull(body.get("description")));
        app.setStatus(ST_PENDING);
        app.setAppliedBy(username);
        app.setAppliedAt(LocalDateTime.now());
        app.setCreatedAt(LocalDateTime.now());
        applicationMapper.insert(app);
        auditService.record(username, "RA_SUBMIT", app.getApplyNo(),
                "提交证书申请：" + applicantName + "（" + applicantType + "，模板 " + tpl.getTemplateCode() + "）", ip, org);
        return app;
    }

    /**
     * 审核批准（审核员；不得与提交人同一人，GM/T 0034 §10.1g）。
     * 批准即固化：把模板策略快照写入申请单，签发按快照执行——批准之后模板被改动/删除
     * 都不影响已批准单实际签出的证书内容（审核人看到的即所批的）。
     * 数据域：操作者域（effOrg，effectiveOrg 归一）必须与申请单归属机构一致，跨机构 403。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertApplication approve(Long id, String comment, String username, String ip, String effOrg) {
        CertApplication app = checkPending(id);
        requireOrgMatch(app, effOrg);
        checkReviewerDiff(app, username);
        CertTemplate tpl = templateService.detailVisible(app.getTemplateId(), app.getOrgKey());
        app.setTemplateSnapshot(snapshotJson(tpl));
        app.setStatus(ST_APPROVED);
        app.setReviewedBy(username);
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(blankToNull(comment));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_APPROVE", app.getApplyNo(),
                "批准证书申请：" + app.getApplicantName() + "（已固化模板策略快照）", ip, app.getOrgKey());
        return app;
    }

    /** 审核驳回（审核员；不得与提交人同一人；申请单必须落在操作者域） */
    public CertApplication reject(Long id, String comment, String username, String ip, String effOrg) {
        CertApplication app = checkPending(id);
        requireOrgMatch(app, effOrg);
        checkReviewerDiff(app, username);
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回必须填写审核意见");
        }
        app.setStatus(ST_REJECTED);
        app.setReviewedBy(username);
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(comment.trim());
        applicationMapper.updateById(app);
        auditService.record(username, "RA_REJECT", app.getApplyNo(),
                "驳回证书申请：" + app.getApplicantName(), ip, app.getOrgKey());
        return app;
    }

    /**
     * 签发：按批准快照 + 公钥签发，私钥由申请方自持（操作员执行）。
     * FOR UPDATE 行锁 + 同事务状态流转，防两个操作员并发对同一「已批准」单双签。
     * 数据域：申请单归属机构必须等于操作者域（跨机构 403）；签发走 CertService 带 orgKey 正式版，
     * 落到申请单受理机构根域。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertVo issue(Long id, String username, String ip, String effOrg) {
        CertApplication app = applicationMapper.selectForUpdate(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireOrgMatch(app, effOrg);
        if (!ST_APPROVED.equals(app.getStatus())) {
            throw new IllegalArgumentException("仅「已批准」状态的申请单可签发（当前状态：" + app.getStatus() + "）");
        }
        PublicKeyIssueRequest req = new PublicKeyIssueRequest();
        req.setSubjectDn(app.getSubjectDn());
        req.setPublicKeyPem(app.getPublicKeyPem());
        req.setTemplateId(app.getTemplateId());
        req.setSan(app.getSan());
        req.setDescription(app.getDescription());
        // 签发一律按快照执行，不重查可变模板最新值；存量单无快照时回退现查模板（模板须仍存在）
        CertService.IssuePolicy policy = parseSnapshot(app);
        // 机构版正式入口：落到申请单受理机构根域（requireOrgMatch 已保证 effOrg==申请单域）
        CertVo vo = certService.issueWithPublicKey(effOrg, req, policy, username, ip);

        app.setStatus(ST_ISSUED);
        app.setIssuedCertId(Long.valueOf(vo.getId()));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_ISSUE", app.getApplyNo(),
                "按申请签发证书：" + app.getApplicantName() + "（证书 id=" + vo.getId() + "）", ip, app.getOrgKey());
        return vo;
    }

    /**
     * 撤销申请：仅申请人本人（录入人）或系统管理员可撤销，
     * 防止任一操作员越权撤销他人经办的申请单。
     * 数据域：申请单归属机构必须等于操作者域（机构用户跨机构 403；全局用户缺省域=default）。
     */
    public CertApplication cancel(Long id, String username, String role, String ip, String effOrg) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        requireOrgMatch(app, effOrg);
        if (!RoleCode.ADMIN.equals(role) && !java.util.Objects.equals(app.getAppliedBy(), username)) {
            throw new SecurityException("仅申请提交人本人或系统管理员可撤销申请（当前操作人：" + username + "）");
        }
        if (!ST_PENDING.equals(app.getStatus()) && !ST_APPROVED.equals(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核/已批准」状态的申请单可撤销（当前状态：" + app.getStatus() + "）");
        }
        app.setStatus(ST_CANCELLED);
        applicationMapper.updateById(app);
        auditService.record(username, "RA_CANCEL", app.getApplyNo(),
                "撤销证书申请：" + app.getApplicantName(), ip, app.getOrgKey());
        return app;
    }

    /** 数据域强制：申请单归属机构必须等于操作者有效域（机构用户越域 403；全局用户须显式传对 orgKey） */
    private static void requireOrgMatch(CertApplication app, String effOrg) {
        String appOrg = app.getOrgKey() == null || app.getOrgKey().isBlank()
                ? CaOrg.DEFAULT_ORG_KEY : app.getOrgKey();
        if (!appOrg.equals(effOrg)) {
            throw new SecurityException("权限不足：申请单归属机构 " + appOrg + "，与当前操作域 " + effOrg + " 不一致");
        }
    }

    /**
     * 机构域申请的主体 DN 受控校验：CN 自由，但必须携带 O 且等于机构受控 subject_o（提交即校验并提示，
     * 避免签发出才被拒）；default 域保持 O=CSSSL 老口径不校验。
     */
    private void checkSubjectDnOrg(String subjectDn, String org) {
        if (CaOrg.DEFAULT_ORG_KEY.equals(org)) {
            return;
        }
        CaOrg orgRow = orgService.get(org);
        X500Name name;
        try {
            name = new X500Name(subjectDn);
        } catch (Exception e) {
            throw new IllegalArgumentException("主体DN格式无效（如 CN=张三,O=" + orgRow.getSubjectO() + ",OU=门诊部）: "
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
            throw new IllegalArgumentException("机构域（" + org + "）申请的主体DN必须携带 O=" + orgRow.getSubjectO()
                    + "（CN 可自由，O 受机构管控，防跨机构冒名）");
        }
        if (!o.equals(orgRow.getSubjectO())) {
            throw new IllegalArgumentException("主体DN的 O（" + o + "）与受理机构受控O（" + orgRow.getSubjectO()
                    + "）不一致，请修正后重新提交");
        }
    }

    private CertApplication checkPending(Long id) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        if (!ST_PENDING.equals(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核」状态的申请单可审核（当前状态：" + app.getStatus() + "）");
        }
        return app;
    }

    /** 录入与审核分离：审核人不得为提交人本人（GM/T 0034 §10.1g 硬要求） */
    private void checkReviewerDiff(CertApplication app, String username) {
        if (username != null && username.equals(app.getAppliedBy())) {
            throw new SecurityException("违反分权制衡：审核人不得与申请提交人为同一人（GM/T 0034 §10.1g）");
        }
    }

    /** 批准时固化模板策略为 JSON 快照（有序字段，便于人工核对与审计比对） */
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
            return snapshotOf(templateService.detailVisible(app.getTemplateId(), app.getOrgKey()));
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

    private String required(Map<String, String> body, String key, String label) {
        String v = body.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 不能为空（" + label + "）");
        }
        return v.trim();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
