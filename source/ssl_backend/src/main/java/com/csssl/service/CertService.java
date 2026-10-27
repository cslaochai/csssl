package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.CssslProperties;
import com.csssl.dto.CertVo;
import com.csssl.dto.IssueCertRequest;
import com.csssl.entity.CaCert;
import com.csssl.entity.CertTemplate;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.asn1.x500.X500Name;

/**
 * 证书签发与生命周期管理：在线签发（内部生成密钥对）/ CSR 签发 / 吊销 / 下载 / 概览统计。
 * 签发私钥只落盘（store-dir/issued/{serial}），数据库只存证书与元信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertService {

    private static final Set<String> TYPES = Set.of("SERVER", "CLIENT", "SUB_CA");
    private static final int MAX_VALIDITY_DAYS = 36500;

    private final CaCertMapper certMapper;
    private final CertTemplateMapper templateMapper;
    private final CaService caService;
    private final CssslProperties props;
    private final AuditService auditService;

    /** 分页查询证书列表 */
    public Page<CertVo> page(long pageNo, long pageSize, String certType, String status, String keyword) {
        LambdaQueryWrapper<CaCert> qw = new LambdaQueryWrapper<>();
        if (certType != null && !certType.isBlank()) {
            qw.eq(CaCert::getCertType, certType);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(CaCert::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CaCert::getSubjectCn, keyword).or().like(CaCert::getSerialHex, keyword));
        }
        qw.orderByDesc(CaCert::getCreatedAt);
        Page<CaCert> page = certMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return (Page<CertVo>) page.convert(CertVo::from);
    }

    /** 证书详情 */
    public CertVo detail(Long id) {
        return CertVo.from(findById(id));
    }

    /** 在线签发：内部生成密钥对，私钥落盘 */
    public CertVo issue(IssueCertRequest req, String username, String ip) {
        String cn = req.getSubjectCn() == null ? "" : req.getSubjectCn().trim();
        if (cn.isEmpty()) {
            throw new IllegalArgumentException("主体CN不能为空");
        }
        CertTemplate tpl = resolveTemplate(req.getTemplateId());
        // 模板锁定：类型/算法/有效期/KeyUsage/EKU/策略OID 全部以模板为准（GM/T 0034 §5.3.4）
        String type = tpl != null ? tpl.getCertType() : (req.getCertType() == null ? "SERVER" : req.getCertType()).toUpperCase();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的证书类型: " + type + "（仅支持 SERVER / CLIENT / SUB_CA）");
        }
        String algorithm = tpl != null ? tpl.getAlgorithm() : (req.getAlgorithm() == null ? CertUtil.ALG_RSA2048 : req.getAlgorithm());
        CertUtil.checkAlgorithm(algorithm);
        int days = tpl != null ? tpl.getValidityDays() : resolveValidityDays(req.getValidityDays());

        KeyPair subjectKey = CertUtil.generateKeyPair(algorithm);
        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        X509Certificate cert = tpl != null
                ? CertUtil.issueByTemplate(
                        new X500Name("CN=" + cn + ",O=CSSSL"), subjectKey.getPublic(), caCert, caKey,
                        type, days, req.getSan(), crlUrl(), ocspUrl(),
                        tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issue(subjectKey, caCert, caKey, cn, type, days,
                        req.getSan(), crlUrl(), ocspUrl());

        String serial = cert.getSerialNumber().toString(16);
        persistFiles(serial, PemUtil.keyToPem(subjectKey.getPrivate()), PemUtil.certToPem(cert));

        CaCert row = buildRow(serial, cn, type, algorithm, caCert, cert, req.getSan(),
                req.getDescription(), username, tpl);
        certMapper.insert(row);
        auditService.record(username, "CERT_ISSUE", String.valueOf(row.getId()),
                "签发 " + type + " 证书：" + cn + "（" + algorithm + (tpl != null ? "，模板 " + tpl.getTemplateCode() : "") + "）", ip);
        log.info("[证书签发] id={}, cn={}, type={}, algorithm={}, serial={}", row.getId(), cn, type, algorithm, serial);
        return CertVo.from(row);
    }

    /** CSR 签发：主体与公钥取自 CSR（密钥由申请方自持，不落盘） */
    public CertVo issueFromCsr(String csrPem, String certType, Integer validityDays,
                               String san, String description, String username, String ip) {
        return issueFromCsr(csrPem, certType, validityDays, san, description, username, ip, null);
    }

    /** CSR 签发（支持模板）：主体与公钥取自 CSR（密钥由申请方自持，不落盘） */
    public CertVo issueFromCsr(String csrPem, String certType, Integer validityDays,
                               String san, String description, String username, String ip, Long templateId) {
        if (csrPem == null || csrPem.isBlank()) {
            throw new IllegalArgumentException("CSR内容不能为空");
        }
        CertTemplate tpl = resolveTemplate(templateId);
        String type = tpl != null ? tpl.getCertType() : (certType == null ? "SERVER" : certType).toUpperCase();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的证书类型: " + certType);
        }
        int days = tpl != null ? tpl.getValidityDays() : resolveValidityDays(validityDays);

        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        String effectiveSan = CertUtil.sanFromCsr(csrPem, san);
        // 证书签名算法跟随签发CA（SUB_CA）密钥类型（RSA SHA256withRSA / 国密 SM3withSM2）
        X509Certificate cert = tpl != null
                ? CertUtil.issueFromCsrByTemplate(csrPem, caCert, caKey, type, days,
                        effectiveSan, crlUrl(), ocspUrl(),
                        tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issueFromCsr(csrPem, caCert, caKey, type, days,
                        effectiveSan, crlUrl(), ocspUrl());

        String serial = cert.getSerialNumber().toString(16);
        // CSR 申请方自持私钥，不落盘
        persistFiles(serial, null, PemUtil.certToPem(cert));
        String cn = CertUtil.subjectCn(cert);
        // CSR 场景密钥由申请方自持，算法从证书公钥类型推导（记录用）
        String algorithm = "EC".equals(cert.getPublicKey().getAlgorithm())
                ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048;

        CaCert row = buildRow(serial, cn, type, algorithm, caCert, cert, effectiveSan,
                description, username, tpl);
        certMapper.insert(row);
        auditService.record(username, "CERT_CSR_ISSUE", String.valueOf(row.getId()),
                "CSR签发 " + type + " 证书：" + cn + (tpl != null ? "（模板 " + tpl.getTemplateCode() + "）" : ""), ip);
        return CertVo.from(row);
    }

    /**
     * 公钥签发（RA 场景）：申请方本地生成密钥对后只提交 Subject DN 与公钥，私钥自持不落盘。
     * 用于 HIS 等业务系统对接（医生签名证书等）。
     */
    public CertVo issueWithPublicKey(com.csssl.dto.PublicKeyIssueRequest req, String username, String ip) {
        String dn = req.getSubjectDn() == null ? "" : req.getSubjectDn().trim();
        if (dn.isEmpty()) {
            throw new IllegalArgumentException("主体DN不能为空（如 CN=张三,O=医院,OU=门诊部）");
        }
        if (req.getPublicKeyPem() == null || req.getPublicKeyPem().isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥）");
        }
        CertTemplate tpl = resolveTemplate(req.getTemplateId());
        String type = tpl != null ? tpl.getCertType() : (req.getCertType() == null ? "CLIENT" : req.getCertType()).toUpperCase();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的证书类型: " + type + "（仅支持 SERVER / CLIENT / SUB_CA）");
        }
        int days = tpl != null ? tpl.getValidityDays() : resolveValidityDays(req.getValidityDays());

        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        X509Certificate cert = tpl != null
                ? CertUtil.issuePublicKeyByTemplate(dn, req.getPublicKeyPem(), caCert, caKey,
                        type, days, null, crlUrl(), ocspUrl(),
                        tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issuePublicKey(dn, req.getPublicKeyPem(), caCert, caKey,
                        type, days, null, crlUrl(), ocspUrl());

        String serial = cert.getSerialNumber().toString(16);
        // 申请方自持私钥，不落盘
        persistFiles(serial, null, PemUtil.certToPem(cert));
        String cn = CertUtil.subjectCn(cert);
        String algorithm = "EC".equals(cert.getPublicKey().getAlgorithm())
                ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048;

        CaCert row = buildRow(serial, cn, type, algorithm, caCert, cert, null,
                req.getDescription(), username, tpl);
        certMapper.insert(row);
        auditService.record(username, "CERT_PUBKEY_ISSUE", String.valueOf(row.getId()),
                "公钥签发 " + type + " 证书：" + cn + (tpl != null ? "（模板 " + tpl.getTemplateCode() + "）" : ""), ip);
        log.info("[公钥签发] id={}, cn={}, type={}, algorithm={}, serial={}", row.getId(), cn, type, algorithm, serial);
        return CertVo.from(row);
    }

    /** 吊销证书 */
    public CertVo revoke(Long id, String reason, String username, String ip) {
        CaCert row = findById(id);
        if (CaService.TYPE_ROOT.equals(row.getCertType())) {
            throw new IllegalArgumentException("根CA证书不允许吊销，如需重建请使用「初始化根CA」");
        }
        if (!"VALID".equals(row.getStatus())) {
            throw new IllegalArgumentException("仅有效状态的证书可以吊销（当前状态：" + row.getStatus() + "）");
        }
        row.setStatus("REVOKED");
        row.setRevokeReason(reason == null || reason.isBlank() ? "UNSPECIFIED" : reason.trim());
        row.setRevokedAt(LocalDateTime.now());
        certMapper.updateById(row);
        auditService.record(username, "CERT_REVOKE", String.valueOf(id),
                "吊销证书：" + row.getSubjectCn() + "，原因：" + row.getRevokeReason(), ip);
        log.info("[证书吊销] id={}, cn={}, reason={}", id, row.getSubjectCn(), row.getRevokeReason());
        return CertVo.from(row);
    }

    /**
     * 证书 PEM（公钥证书）：返回证书链（叶子 + 签发SUB_CA），供 TLS 服务端部署/业务系统导入。
     */
    public String certPem(Long id) {
        CaCert row = findById(id);
        StringBuilder chain = new StringBuilder(row.getCertPem());
        if (!CaService.TYPE_ROOT.equals(row.getCertType())) {
            CaCert subCa = matchingSubCa(row);
            if (subCa != null) {
                chain.append(subCa.getCertPem());
            }
        }
        return chain.toString();
    }

    /** 私钥 PEM（受 allow-private-key-download 开关控制，从加密保险库解密读取） */
    public String keyPem(Long id) {
        if (!props.isAllowPrivateKeyDownload()) {
            throw new IllegalArgumentException("私钥下载已关闭（csssl.allow-private-key-download=false）");
        }
        CaCert row = findById(id);
        if (CaService.TYPE_ROOT.equals(row.getCertType())) {
            throw new IllegalArgumentException("根CA私钥为系统核心密钥，不提供在线下载，请直接操作服务器文件");
        }
        if (!caService.issuedKeyExists(row.getSerialHex())) {
            throw new IllegalArgumentException("该证书无落盘私钥（CSR签发场景私钥由申请方自持）");
        }
        String keyPem = caService.loadIssuedKey(row.getSerialHex());
        if (keyPem == null) {
            throw new IllegalArgumentException("该证书私钥不可用（已封存或缺失）");
        }
        return keyPem;
    }

    /** 匹配叶子证书的签发 SUB_CA（按 issuer 精确匹配，找不到时退回当前 SUB_CA） */
    private CaCert matchingSubCa(CaCert leaf) {
        if (leaf.getIssuerCn() == null || leaf.getIssuerCn().isBlank()) {
            return caService.currentSubCa();
        }
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, "SUB_CA")
                .eq(CaCert::getSubjectCn, leaf.getIssuerCn())
                .orderByDesc(CaCert::getCreatedAt));
        return subs.isEmpty() ? caService.currentSubCa() : subs.get(0);
    }

    /** 概览统计：证书总量/状态分布/类型分布/30天内到期，供仪表盘渲染 */
    public Map<String, Object> overview() {
        List<CaCert> all = certMapper.selectList(null);
        long valid = 0, revoked = 0, expired = 0;
        long server = 0, client = 0, subCa = 0;
        long expiring = 0;
        LocalDateTime deadline = LocalDateTime.now().plusDays(30);
        for (CaCert c : all) {
            switch (c.getStatus()) {
                case "VALID" -> valid++;
                case "REVOKED" -> revoked++;
                case "EXPIRED" -> expired++;
                default -> { }
            }
            switch (c.getCertType()) {
                case "SERVER" -> server++;
                case "CLIENT" -> client++;
                case "SUB_CA" -> subCa++;
                default -> { }
            }
            if ("VALID".equals(c.getStatus()) && c.getValidTo() != null
                    && c.getValidTo().isBefore(deadline)) {
                expiring++;
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", all.size());
        data.put("valid", valid);
        data.put("revoked", revoked);
        data.put("expired", expired);
        data.put("server", server);
        data.put("client", client);
        data.put("subCa", subCa);
        data.put("expiring30d", expiring);
        data.put("root", caService.status());
        return data;
    }

    /** 按ID查询证书 */
    public CaCert findById(Long id) {
        CaCert row = certMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("证书不存在（id=" + id + "）");
        }
        return row;
    }

    private int resolveValidityDays(Integer validityDays) {
        int days = validityDays == null ? props.getIssue().getDefaultValidityDays() : validityDays;
        if (days <= 0 || days > MAX_VALIDITY_DAYS) {
            throw new IllegalArgumentException("证书有效期需在 1~" + MAX_VALIDITY_DAYS + " 天之间");
        }
        return days;
    }

    private void persistFiles(String serial, String keyPem, String certPem) {
        try {
            Path dir = caService.issuedDir(serial);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("cert.pem"), certPem, StandardCharsets.UTF_8);
            if (keyPem != null) {
                // 私钥经密钥保险库 SM4 加密落盘，不写明文
                caService.storeIssuedKey(serial, keyPem);
            }
        } catch (IOException e) {
            throw new IllegalStateException("证书落盘失败: " + e.getMessage(), e);
        }
    }

    /** CRL 分发点 URL（写入证书 CDP 扩展） */
    private String crlUrl() {
        return props.getPublicBaseUrl() + "/ca/public/crl";
    }

    /** OCSP 访问点 URL（写入证书 AIA 扩展） */
    private String ocspUrl() {
        return props.getPublicBaseUrl() + "/ca/public/ocsp";
    }

    private CaCert buildRow(String serial, String cn, String type, String algorithm,
                            X509Certificate caCert, X509Certificate cert, String san,
                            String description, String username, CertTemplate tpl) {
        CaCert row = new CaCert();
        row.setSerialHex(serial);
        row.setSubjectCn(cn);
        row.setCertType(type);
        row.setAlgorithm(algorithm);
        row.setIssuerCn(CertUtil.subjectCn(caCert));
        row.setSan(san == null ? null : san.trim());
        row.setValidFrom(LocalDateTime.now());
        row.setValidTo(cert.getNotAfter().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        row.setStatus("VALID");
        row.setCertPem(PemUtil.certToPem(cert));
        row.setDescription(description);
        row.setTemplateId(tpl == null ? null : tpl.getId());
        row.setCreatedBy(username);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /** 按ID解析证书模板；templateId 为空返回 null（兼容不走模板的直签） */
    private CertTemplate resolveTemplate(Long templateId) {
        if (templateId == null) {
            return null;
        }
        CertTemplate tpl = templateMapper.selectById(templateId);
        if (tpl == null) {
            throw new IllegalArgumentException("证书模板不存在（id=" + templateId + "）");
        }
        return tpl;
    }
}
