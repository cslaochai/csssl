package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.PublicKeyIssueRequest;
import com.csssl.dto.CertVo;
import com.csssl.entity.CertApplication;
import com.csssl.entity.CertTemplate;
import com.csssl.mapper.CertApplicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * RA 用户注册流程（GM/T 0034 §5.2.2 用户注册管理系统）：
 * 提交（录入员）→ 审核批准/驳回（审核员，§10.1g 不得与录入员同一人）→ 签发（操作员，复用模板与公钥签发）。
 * 申请单状态机：PENDING → APPROVED / REJECTED → ISSUED / CANCELLED。
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
    private final AuditService auditService;

    /** 分页查询申请单 */
    public Page<CertApplication> page(long pageNo, long pageSize, String status, String keyword) {
        LambdaQueryWrapper<CertApplication> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CertApplication::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CertApplication::getApplicantName, keyword).or()
                    .like(CertApplication::getApplyNo, keyword));
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

    /** 提交申请（录入员代录：医生/机构提供材料，操作员录入系统） */
    public CertApplication submit(Map<String, String> body, String username, String ip) {
        String applicantType = body.get("applicantType");
        if (!"INSTITUTION".equals(applicantType) && !"PERSON".equals(applicantType)) {
            throw new IllegalArgumentException("申请人类别仅支持 INSTITUTION-机构 / PERSON-个人");
        }
        String subjectDn = required(body, "subjectDn", "主体DN（如 CN=张三,O=麓康医院,OU=门诊部）");
        String idNo = required(body, "idNo", applicantType.equals("INSTITUTION") ? "统一社会信用代码" : "身份证号");
        String applicantName = required(body, "applicantName", "申请人/机构名称");
        Long templateId = Long.parseLong(required(body, "templateId", "证书模板ID"));
        CertTemplate tpl = templateService.detail(templateId);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new IllegalArgumentException("SUB_CA 证书不走 RA 申请流程，请由系统管理员直接签发");
        }
        String publicKeyPem = body.get("publicKeyPem");
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥；私钥不出服务器更合规）");
        }

        CertApplication app = new CertApplication();
        app.setApplyNo("RA" + APPLY_NO_TS.format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000)));
        app.setApplicantType(applicantType);
        app.setApplicantName(applicantName.trim());
        app.setIdNo(idNo.trim());
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
                "提交证书申请：" + applicantName + "（" + applicantType + "，模板 " + tpl.getTemplateCode() + "）", ip);
        return app;
    }

    /** 审核批准（审核员；不得与提交人同一人，GM/T 0034 §10.1g） */
    public CertApplication approve(Long id, String comment, String username, String ip) {
        CertApplication app = checkPending(id);
        checkReviewerDiff(app, username);
        app.setStatus(ST_APPROVED);
        app.setReviewedBy(username);
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(blankToNull(comment));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_APPROVE", app.getApplyNo(), "批准证书申请：" + app.getApplicantName(), ip);
        return app;
    }

    /** 审核驳回（审核员；不得与提交人同一人） */
    public CertApplication reject(Long id, String comment, String username, String ip) {
        CertApplication app = checkPending(id);
        checkReviewerDiff(app, username);
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("驳回必须填写审核意见");
        }
        app.setStatus(ST_REJECTED);
        app.setReviewedBy(username);
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewComment(comment.trim());
        applicationMapper.updateById(app);
        auditService.record(username, "RA_REJECT", app.getApplyNo(), "驳回证书申请：" + app.getApplicantName(), ip);
        return app;
    }

    /** 签发：复用模板 + 公钥签发，私钥由申请方自持（操作员执行） */
    public CertVo issue(Long id, String username, String ip) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        if (!ST_APPROVED.equals(app.getStatus())) {
            throw new IllegalArgumentException("仅「已批准」状态的申请单可签发（当前状态：" + app.getStatus() + "）");
        }
        PublicKeyIssueRequest req = new PublicKeyIssueRequest();
        req.setSubjectDn(app.getSubjectDn());
        req.setPublicKeyPem(app.getPublicKeyPem());
        req.setTemplateId(app.getTemplateId());
        req.setDescription(app.getDescription());
        CertVo vo = certService.issueWithPublicKey(req, username, ip);

        app.setStatus(ST_ISSUED);
        app.setIssuedCertId(Long.valueOf(vo.getId()));
        applicationMapper.updateById(app);
        auditService.record(username, "RA_ISSUE", app.getApplyNo(),
                "按申请签发证书：" + app.getApplicantName() + "（证书 id=" + vo.getId() + "）", ip);
        return vo;
    }

    /** 撤销申请（录入员可在批准前撤回） */
    public CertApplication cancel(Long id, String username, String ip) {
        CertApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new IllegalArgumentException("申请单不存在（id=" + id + "）");
        }
        if (!ST_PENDING.equals(app.getStatus()) && !ST_APPROVED.equals(app.getStatus())) {
            throw new IllegalArgumentException("仅「待审核/已批准」状态的申请单可撤销（当前状态：" + app.getStatus() + "）");
        }
        app.setStatus(ST_CANCELLED);
        applicationMapper.updateById(app);
        auditService.record(username, "RA_CANCEL", app.getApplyNo(), "撤销证书申请：" + app.getApplicantName(), ip);
        return app;
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
