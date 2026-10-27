package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.CertTemplate;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.util.CertUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 证书模板管理（GM/T 0034 §5.3.4 证书策略管理 / GM/T 0015 证书格式）：
 * 固化证书类型/算法/有效期/KeyUsage/EKU/策略OID，签发必须按模板进行。
 */
@Service
@RequiredArgsConstructor
public class CertTemplateService {

    private static final Set<String> CERT_TYPES = Set.of("SERVER", "CLIENT", "SUB_CA");
    private static final Set<String> ALGORITHMS = Set.of(CertUtil.ALG_RSA2048, CertUtil.ALG_SM2);

    private final CertTemplateMapper templateMapper;

    /** 分页查询模板 */
    public Page<CertTemplate> page(long pageNo, long pageSize, String keyword) {
        LambdaQueryWrapper<CertTemplate> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CertTemplate::getTemplateName, keyword).or()
                    .like(CertTemplate::getTemplateCode, keyword));
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

    /** 创建模板（仅系统管理员） */
    public CertTemplate create(Map<String, String> body) {
        CertTemplate tpl = new CertTemplate();
        tpl.setTemplateCode(required(body, "templateCode"));
        tpl.setTemplateName(required(body, "templateName"));
        tpl.setCertType(validateType(body.get("certType")));
        tpl.setAlgorithm(validateAlgorithm(body.get("algorithm") == null || body.get("algorithm").isBlank()
                ? CertUtil.ALG_SM2 : body.get("algorithm").trim().toUpperCase()));
        tpl.setValidityDays(parseDays(body.get("validityDays")));
        tpl.setKeyUsage(validateKeyUsage(body.get("keyUsage")));
        String eku = body.get("extKeyUsage");
        if (eku != null && !eku.isBlank()) {
            // 校验 EKU 合法性（SUB_CA 不应配置 EKU）
            if ("SUB_CA".equals(tpl.getCertType())) {
                throw new IllegalArgumentException("SUB_CA 模板不应配置 EKU");
            }
            CertUtil.ekuPurpose(eku);
            tpl.setExtKeyUsage(eku.trim().toLowerCase());
        }
        tpl.setPolicyOid(blankToNull(body.get("policyOid")));
        tpl.setBuiltin(0);
        tpl.setDescription(blankToNull(body.get("description")));
        tpl.setCreatedAt(LocalDateTime.now());
        if (templateMapper.selectCount(new LambdaQueryWrapper<CertTemplate>()
                .eq(CertTemplate::getTemplateCode, tpl.getTemplateCode())) > 0) {
            throw new IllegalArgumentException("模板编码已存在：" + tpl.getTemplateCode());
        }
        templateMapper.insert(tpl);
        return tpl;
    }

    /** 更新模板（仅系统管理员；内置模板可更新参数但编码与类型不可改） */
    public CertTemplate update(Long id, Map<String, String> body) {
        CertTemplate tpl = detail(id);
        if (body.containsKey("templateName") && !body.get("templateName").isBlank()) {
            tpl.setTemplateName(body.get("templateName").trim());
        }
        if (body.containsKey("algorithm") && !body.get("algorithm").isBlank()) {
            tpl.setAlgorithm(validateAlgorithm(body.get("algorithm").trim().toUpperCase()));
        }
        if (body.containsKey("validityDays")) {
            tpl.setValidityDays(parseDays(body.get("validityDays")));
        }
        if (body.containsKey("keyUsage") && !body.get("keyUsage").isBlank()) {
            tpl.setKeyUsage(validateKeyUsage(body.get("keyUsage")));
        }
        if (body.containsKey("extKeyUsage")) {
            String eku = body.get("extKeyUsage");
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
        if (body.containsKey("policyOid")) {
            tpl.setPolicyOid(blankToNull(body.get("policyOid")));
        }
        if (body.containsKey("description")) {
            tpl.setDescription(blankToNull(body.get("description")));
        }
        templateMapper.updateById(tpl);
        return tpl;
    }

    /** 删除模板（仅系统管理员；内置模板不可删除） */
    public void delete(Long id) {
        CertTemplate tpl = detail(id);
        if (tpl.getBuiltin() != null && tpl.getBuiltin() == 1) {
            throw new IllegalArgumentException("内置模板（" + tpl.getTemplateCode() + "）不可删除，可停用或改参数");
        }
        templateMapper.deleteById(id);
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

    private int parseDays(String v) {
        int days = v == null || v.isBlank() ? 365 : Integer.parseInt(v.trim());
        if (days <= 0 || days > 36500) {
            throw new IllegalArgumentException("有效期需在 1~36500 天之间");
        }
        return days;
    }

    private String required(Map<String, String> body, String key) {
        String v = body.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 不能为空");
        }
        return v.trim();
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
