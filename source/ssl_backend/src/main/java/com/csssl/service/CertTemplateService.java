package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.dto.TemplateCreateDTO;
import com.csssl.dto.TemplateUpdateDTO;
import com.csssl.entity.CaOrg;
import com.csssl.entity.CertTemplate;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.util.CertUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 证书模板管理（GM/T 0034 §5.3.4 证书策略管理 / GM/T 0015 证书格式）：
 * 固化证书类型/算法/有效期/KeyUsage/EKU/策略OID，签发必须按模板进行。
 *
 * <p>模板决定证书用途与密钥用法，属策略变更，故增删改一律落审计（TEMPLATE_*）。</p>
 *
 * <p>路线A数据域：ssl_cert_template.org_key NULL=全局模板（所有机构可见，内置模板保持全局）；
 * 机构模板仅本机构可见。机构 SECURITY_ADMIN 创建/修改/删除仅限本机构模板（不可改全局模板）；
 * 全局 ADMIN/SECURITY_ADMIN 可管全局模板与各机构模板（模板域用 orgKey 指定，全局建模板 orgKey 传空=全局）。
 * 可见集过滤=全局模板 ∪ 本机构模板；RA/签发引用模板时必须校验"该模板对该机构可见"。</p>
 */
@Service
@RequiredArgsConstructor
public class CertTemplateService {

    private static final Set<String> CERT_TYPES = Set.of("SERVER", "CLIENT", "SUB_CA");
    private static final Set<String> ALGORITHMS = Set.of(CertUtil.ALG_RSA2048, CertUtil.ALG_SM2);

    private final CertTemplateMapper templateMapper;
    private final OrgService orgService;
    private final AuditService auditService;

    /** 分页查询模板（兼容入口=全量，仅全局用户经控制器到达） */
    public Page<CertTemplate> page(long pageNo, long pageSize, String keyword) {
        return page(pageNo, pageSize, keyword, null);
    }

    /**
     * 分页查询模板（机构域版）：orgScope=null 跨机构全量（全局用户总览，只读放行）；
     * orgScope 非空=可见集过滤——全局模板（org_key NULL）+ 该机构模板。
     */
    public Page<CertTemplate> page(long pageNo, long pageSize, String keyword, String orgScope) {
        LambdaQueryWrapper<CertTemplate> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CertTemplate::getTemplateName, keyword).or()
                    .like(CertTemplate::getTemplateCode, keyword));
        }
        if (orgScope != null && !orgScope.isBlank()) {
            qw.and(w -> w.isNull(CertTemplate::getOrgKey).or().eq(CertTemplate::getOrgKey, orgScope));
        }
        qw.orderByAsc(CertTemplate::getTemplateCode);
        return templateMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 按ID查询模板 */
    public CertTemplate detail(Long id) {
        CertTemplate tpl = templateMapper.selectById(id);
        if (tpl == null) {
            throw new IllegalArgumentException("证书模板不存在（id=" + id + "）");
        }
        return tpl;
    }

    /**
     * 按数据域取模板（RA/签发/详情引用模板的统一入口）：
     * 机构域仅可见全局模板与本机构模板，其余按"不存在"处理（不泄露他机构模板存在性）。
     */
    public CertTemplate detailVisible(Long id, String orgScope) {
        CertTemplate tpl = detail(id);
        if (!visibleTo(tpl, orgScope)) {
            throw new IllegalArgumentException("证书模板不存在（id=" + id + "）");
        }
        return tpl;
    }

    /** 模板对操作域是否可见：全局模板（org_key NULL）人人可见；机构模板仅归属机构可见；全局域（null）全可见 */
    public static boolean visibleTo(CertTemplate tpl, String operatorOrg) {
        if (operatorOrg == null || operatorOrg.isBlank()) {
            return true;
        }
        return tpl.getOrgKey() == null || tpl.getOrgKey().isBlank() || tpl.getOrgKey().equals(operatorOrg);
    }

    /** 模板行归属机构兜底（NULL=全局模板，审计归位用） */
    private static String rowOrg(CertTemplate tpl) {
        return tpl.getOrgKey() == null || tpl.getOrgKey().isBlank() ? CaOrg.PRIMARY_ORG_KEY : tpl.getOrgKey();
    }

    /**
     * 创建模板（仅安全管理员/系统管理员；变更落审计）。
     * tplOrg=模板归属域：机构操作者已由 effectiveOrg 锁本机构；全局操作者传 null=全局模板、
     * 非空=为该机构建模板（机构必须存在）。
     */
    public CertTemplate create(TemplateCreateDTO body, String operator, String ip,
                               String tplOrg, String operatorOrg) {
        if (tplOrg != null && !tplOrg.isBlank()) {
            orgService.get(tplOrg);
        }
        CertTemplate tpl = new CertTemplate();
        tpl.setOrgKey(tplOrg == null || tplOrg.isBlank() ? null : tplOrg);
        tpl.setTemplateCode(required(body.getTemplateCode(), "templateCode"));
        tpl.setTemplateName(required(body.getTemplateName(), "templateName"));
        tpl.setCertType(validateType(body.getCertType()));
        tpl.setAlgorithm(validateAlgorithm(body.getAlgorithm() == null || body.getAlgorithm().isBlank()
                ? CertUtil.ALG_SM2 : body.getAlgorithm().trim().toUpperCase()));
        tpl.setValidityDays(parseDays(body.getValidityDays()));
        tpl.setKeyUsage(validateKeyUsage(body.getKeyUsage()));
        String eku = body.getExtKeyUsage();
        if (eku != null && !eku.isBlank()) {
            // 校验 EKU 合法性（SUB_CA 不应配置 EKU）
            if ("SUB_CA".equals(tpl.getCertType())) {
                throw new IllegalArgumentException("SUB_CA 模板不应配置 EKU");
            }
            CertUtil.ekuPurpose(eku);
            tpl.setExtKeyUsage(eku.trim().toLowerCase());
        }
        tpl.setPolicyOid(blankToNull(body.getPolicyOid()));
        tpl.setBuiltin(0);
        tpl.setDescription(blankToNull(body.getDescription()));
        tpl.setCreatedAt(LocalDateTime.now());
        if (templateMapper.selectCount(new LambdaQueryWrapper<CertTemplate>()
                .eq(CertTemplate::getTemplateCode, tpl.getTemplateCode())) > 0) {
            throw new IllegalArgumentException("模板编码已存在：" + tpl.getTemplateCode());
        }
        templateMapper.insert(tpl);
        auditService.record(operator, "TEMPLATE_CREATE", String.valueOf(tpl.getId()),
                "创建证书模板：" + tpl.getTemplateCode() + "（" + tpl.getTemplateName() + "）"
                        + "，类型=" + tpl.getCertType() + "，算法=" + tpl.getAlgorithm()
                        + "，有效期=" + tpl.getValidityDays() + "天"
                        + "，KeyUsage=" + tpl.getKeyUsage() + "，EKU=" + nz(tpl.getExtKeyUsage())
                        + "，策略OID=" + nz(tpl.getPolicyOid())
                        + "，归属=" + (tpl.getOrgKey() == null ? "全局" : tpl.getOrgKey()),
                ip, rowOrg(tpl));
        return tpl;
    }

    /**
     * 更新模板（仅安全管理员/系统管理员；内置模板可更新参数但编码与类型不可改）。
     * 数据域：机构操作者（operatorOrg 非空）仅可改本机构模板，全局模板与其他机构模板不可触碰；
     * 全局操作者可改全局模板与各机构模板。
     */
    public CertTemplate update(Long id, TemplateUpdateDTO body, String operator, String ip, String operatorOrg) {
        CertTemplate tpl = detail(id);
        requireManaged(tpl, operatorOrg);
        // 变更前后快照对比，审计 detail 只记真正变化的关键项
        String oldName = tpl.getTemplateName();
        String oldAlgorithm = tpl.getAlgorithm();
        Integer oldDays = tpl.getValidityDays();
        String oldKeyUsage = tpl.getKeyUsage();
        String oldEku = tpl.getExtKeyUsage();
        String oldPolicyOid = tpl.getPolicyOid();
        String oldDescription = tpl.getDescription();

        if (body.getTemplateName() != null && !body.getTemplateName().isBlank()) {
            tpl.setTemplateName(body.getTemplateName().trim());
        }
        if (body.getAlgorithm() != null && !body.getAlgorithm().isBlank()) {
            tpl.setAlgorithm(validateAlgorithm(body.getAlgorithm().trim().toUpperCase()));
        }
        if (body.getValidityDays() != null) {
            tpl.setValidityDays(parseDays(body.getValidityDays()));
        }
        if (body.getKeyUsage() != null && !body.getKeyUsage().isBlank()) {
            tpl.setKeyUsage(validateKeyUsage(body.getKeyUsage()));
        }
        if (body.getExtKeyUsage() != null) {
            String eku = body.getExtKeyUsage();
            if (eku != null && !eku.isBlank()) {
                if ("SUB_CA".equals(tpl.getCertType())) {
                    throw new IllegalArgumentException("SUB_CA 模板不应配置 EKU");
                }
                CertUtil.ekuPurpose(eku);
                tpl.setExtKeyUsage(eku.trim().toLowerCase());
            } else {
                tpl.setExtKeyUsage(null);
            }
        }
        if (body.getPolicyOid() != null) {
            tpl.setPolicyOid(blankToNull(body.getPolicyOid()));
        }
        if (body.getDescription() != null) {
            tpl.setDescription(blankToNull(body.getDescription()));
        }
        templateMapper.updateById(tpl);
        List<String> changes = new ArrayList<>();
        addChange(changes, "名称", oldName, tpl.getTemplateName());
        addChange(changes, "算法", oldAlgorithm, tpl.getAlgorithm());
        addChange(changes, "有效期(天)", oldDays == null ? null : String.valueOf(oldDays),
                tpl.getValidityDays() == null ? null : String.valueOf(tpl.getValidityDays()));
        addChange(changes, "KeyUsage", oldKeyUsage, tpl.getKeyUsage());
        addChange(changes, "EKU", oldEku, tpl.getExtKeyUsage());
        addChange(changes, "策略OID", oldPolicyOid, tpl.getPolicyOid());
        addChange(changes, "描述", oldDescription, tpl.getDescription());
        auditService.record(operator, "TEMPLATE_UPDATE", String.valueOf(id),
                "更新证书模板：" + tpl.getTemplateCode() + "（" + tpl.getTemplateName() + "）"
                        + (changes.isEmpty() ? "，无字段变化" : "；变化：" + String.join("；", changes)),
                ip, rowOrg(tpl));
        return tpl;
    }

    /**
     * 删除模板（仅安全管理员/系统管理员；内置模板不可删除）。
     * 数据域：机构操作者仅可删本机构模板；全局操作者可删各机构自定义模板（全局模板同样受内置保护）。
     */
    public void delete(Long id, String operator, String ip, String operatorOrg) {
        CertTemplate tpl = detail(id);
        requireManaged(tpl, operatorOrg);
        if (tpl.getBuiltin() != null && tpl.getBuiltin() == 1) {
            throw new IllegalArgumentException("内置模板（" + tpl.getTemplateCode() + "）不可删除，可停用或改参数");
        }
        templateMapper.deleteById(id);
        auditService.record(operator, "TEMPLATE_DELETE", String.valueOf(id),
                "删除证书模板：" + tpl.getTemplateCode() + "（" + tpl.getTemplateName() + "）"
                        + "，类型=" + tpl.getCertType() + "，算法=" + tpl.getAlgorithm()
                        + "，有效期=" + tpl.getValidityDays() + "天", ip, rowOrg(tpl));
    }

    /** 管理权校验：机构操作者（org 非空）仅能管理"本机构"模板——全局模板（org_key NULL）与其他机构模板一律 403 */
    private static void requireManaged(CertTemplate tpl, String operatorOrg) {
        if (operatorOrg == null || operatorOrg.isBlank()) {
            return;
        }
        if (tpl.getOrgKey() == null || tpl.getOrgKey().isBlank() || !tpl.getOrgKey().equals(operatorOrg)) {
            throw new SecurityException("权限不足：机构管理员仅能管理本机构模板");
        }
    }

    private static void addChange(List<String> changes, String label, String before, String after) {
        if (before == null ? after == null : before.equals(after)) {
            return;
        }
        changes.add(label + "：" + nz(before) + "→" + nz(after));
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "空" : s;
    }

    private String validateType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!CERT_TYPES.contains(t)) {
            throw new IllegalArgumentException("证书类型仅支持 SERVER / CLIENT / SUB_CA");
        }
        return t;
    }

    private String validateAlgorithm(String algorithm) {
        if (!ALGORITHMS.contains(algorithm)) {
            throw new IllegalArgumentException("算法仅支持 RSA2048 / SM2");
        }
        return algorithm;
    }

    private String validateKeyUsage(String keyUsage) {
        if (keyUsage == null || keyUsage.isBlank()) {
            throw new IllegalArgumentException("证书模板必须配置 KeyUsage");
        }
        CertUtil.keyUsageBits(keyUsage);
        return keyUsage.trim();
    }

    private int parseDays(Integer v) {
        int days = v == null ? 365 : v;
        if (days <= 0 || days > 36500) {
            throw new IllegalArgumentException("有效期需在 1~36500 天之间");
        }
        return days;
    }

    private String required(String v, String key) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 不能为空");
        }
        return v.trim();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
