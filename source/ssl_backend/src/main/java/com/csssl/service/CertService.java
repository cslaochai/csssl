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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /**
     * 签发策略快照：RA 批准时从模板固化（certType/algorithm/validityDays/keyUsage/extKeyUsage/policyOid），
     * 签发按快照执行，杜绝批准后被改动的可变模板影响已批准申请（GM/T 0034 §5.3.4 策略固化）。
     */
    public record IssuePolicy(String certType, String algorithm, Integer validityDays,
                              String keyUsage, String extKeyUsage, String policyOid) {
    }

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
                req.getDescription(), username, tpl == null ? null : tpl.getId());
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
                description, username, tpl == null ? null : tpl.getId());
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
        return issueWithPublicKey(req, null, username, ip);
    }

    /**
     * 公钥签发（RA 场景，支持批准快照）：
     * policy 非空时签发参数一律按快照执行（批准时固化的模板策略，不重查可变模板最新值，
     * 保证审核人批准的内容与操作员最终签出的内容一致）；policy 为空时按 templateId 现查模板。
     */
    public CertVo issueWithPublicKey(com.csssl.dto.PublicKeyIssueRequest req, IssuePolicy policy,
                                     String username, String ip) {
        String dn = req.getSubjectDn() == null ? "" : req.getSubjectDn().trim();
        if (dn.isEmpty()) {
            throw new IllegalArgumentException("主体DN不能为空（如 CN=张三,O=医院,OU=门诊部）");
        }
        if (req.getPublicKeyPem() == null || req.getPublicKeyPem().isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥）");
        }
        CertTemplate tpl = policy == null ? resolveTemplate(req.getTemplateId()) : null;
        String type = policy != null ? (policy.certType() == null ? "" : policy.certType().toUpperCase())
                : (tpl != null ? tpl.getCertType() : (req.getCertType() == null ? "CLIENT" : req.getCertType()).toUpperCase());
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的证书类型: " + type + "（仅支持 SERVER / CLIENT / SUB_CA）");
        }
        if (policy != null && policy.algorithm() != null && !policy.algorithm().isBlank()) {
            CertUtil.checkAlgorithm(policy.algorithm());
        }
        int days = policy != null ? resolveValidityDays(policy.validityDays())
                : (tpl != null ? tpl.getValidityDays() : resolveValidityDays(req.getValidityDays()));
        String keyUsage = policy != null ? policy.keyUsage() : (tpl != null ? tpl.getKeyUsage() : null);
        String extKeyUsage = policy != null ? policy.extKeyUsage() : (tpl != null ? tpl.getExtKeyUsage() : null);
        String policyOid = policy != null ? policy.policyOid() : (tpl != null ? tpl.getPolicyOid() : null);

        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        // SAN 透传：申请单/请求里带的 SAN 必须写进证书，此前模板分支写死 null 属丢弃申请内容
        X509Certificate cert = policy != null || tpl != null
                ? CertUtil.issuePublicKeyByTemplate(dn, req.getPublicKeyPem(), caCert, caKey,
                        type, days, req.getSan(), crlUrl(), ocspUrl(), keyUsage, extKeyUsage, policyOid)
                : CertUtil.issuePublicKey(dn, req.getPublicKeyPem(), caCert, caKey,
                        type, days, req.getSan(), crlUrl(), ocspUrl());

        String serial = cert.getSerialNumber().toString(16);
        // 申请方自持私钥，不落盘
        persistFiles(serial, null, PemUtil.certToPem(cert));
        String cn = CertUtil.subjectCn(cert);
        String algorithm = "EC".equals(cert.getPublicKey().getAlgorithm())
                ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048;

        CaCert row = buildRow(serial, cn, type, algorithm, caCert, cert, req.getSan(),
                req.getDescription(), username, policy != null ? req.getTemplateId() : (tpl == null ? null : tpl.getId()));
        certMapper.insert(row);
        auditService.record(username, "CERT_PUBKEY_ISSUE", String.valueOf(row.getId()),
                "公钥签发 " + type + " 证书：" + cn + (policy != null ? "（按批准快照）" : (tpl != null ? "（模板 " + tpl.getTemplateCode() + "）" : "")), ip);
        log.info("[公钥签发] id={}, cn={}, type={}, algorithm={}, serial={}, snapshot={}",
                row.getId(), cn, type, algorithm, serial, policy != null);
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
        markRevoked(row, reason, username);
        auditService.record(username, "CERT_REVOKE", String.valueOf(id),
                "吊销证书：" + row.getSubjectCn() + "，原因：" + row.getRevokeReason(), ip);
        log.info("[证书吊销] id={}, cn={}, reason={}", id, row.getSubjectCn(), row.getRevokeReason());
        return CertVo.from(row);
    }

    /**
     * 挂起证书（GM/T 0034 §12.6）：有效期内暂停使用，不销毁。
     * 挂起期间证书进入 CRL（reason=certificateHold）、OCSP 返回 revoked/on-hold，解除后恢复。
     */
    public CertVo suspend(Long id, String reason, String username, String ip) {
        CaCert row = findById(id);
        if (CaService.TYPE_ROOT.equals(row.getCertType())) {
            throw new IllegalArgumentException("根CA证书不支持挂起");
        }
        if (!"VALID".equals(row.getStatus())) {
            throw new IllegalArgumentException("仅有效状态的证书可以挂起（当前状态：" + row.getStatus() + "）");
        }
        row.setStatus("FROZEN");
        row.setRevokeReason(reason == null || reason.isBlank() ? "CERTIFICATE_HOLD" : reason.trim());
        row.setRevokedAt(LocalDateTime.now());
        certMapper.updateById(row);
        auditService.record(username, "CERT_SUSPEND", String.valueOf(id),
                "挂起证书：" + row.getSubjectCn() + "（CRL certificateHold / OCSP on-hold）", ip);
        log.info("[证书挂起] id={}, cn={}", id, row.getSubjectCn());
        return CertVo.from(row);
    }

    /** 解除挂起（GM/T 0034 §12.7）：恢复有效，从 CRL 移除、OCSP 恢复 good */
    public CertVo unsuspend(Long id, String username, String ip) {
        CaCert row = findById(id);
        if (!"FROZEN".equals(row.getStatus())) {
            throw new IllegalArgumentException("仅挂起状态的证书可以解除挂起（当前状态：" + row.getStatus() + "）");
        }
        row.setStatus("VALID");
        row.setRevokeReason(null);
        row.setRevokedAt(null);
        certMapper.updateById(row);
        auditService.record(username, "CERT_UNSUSPEND", String.valueOf(id),
                "解除挂起证书：" + row.getSubjectCn() + "（恢复有效）", ip);
        log.info("[解除挂起] id={}, cn={}", id, row.getSubjectCn());
        return CertVo.from(row);
    }

    /**
     * 证书更新（GM/T 0034 §12.2 更新）：原证书吊销（SUPERSEDED）+ 以原公钥重签新证书延长有效期。
     * 适用场景：证书即将到期、主体不变；私钥由申请方自持（CSR/公钥签发）也可安全更新。
     */
    public CertVo renew(Long id, Integer validityDays, String username, String ip) {
        CaCert old = findById(id);
        if (CaService.TYPE_ROOT.equals(old.getCertType())) {
            throw new IllegalArgumentException("根CA证书不支持在线更新");
        }
        if (!"VALID".equals(old.getStatus()) && !"FROZEN".equals(old.getStatus())) {
            throw new IllegalArgumentException("仅有效/挂起状态的证书可以更新（当前状态：" + old.getStatus() + "）");
        }
        X509Certificate oldCert = PemUtil.parseCert(old.getCertPem());
        int days = resolveValidityDays(validityDays);
        CertTemplate tpl = old.getTemplateId() == null ? null : templateMapper.selectById(old.getTemplateId());

        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        X509Certificate cert = tpl != null
                ? CertUtil.issueByTemplate(
                        new X500Name(oldCert.getSubjectX500Principal().getName()),
                        oldCert.getPublicKey(), caCert, caKey, old.getCertType(), days,
                        old.getSan(), crlUrl(), ocspUrl(),
                        tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issue(
                        new X500Name(oldCert.getSubjectX500Principal().getName()),
                        oldCert.getPublicKey(), caCert, caKey, old.getCertType(), days,
                        old.getSan(), crlUrl(), ocspUrl());

        markRevoked(old, "SUPERSEDED", username);
        String serial = cert.getSerialNumber().toString(16);
        // 同公钥重签：申请方私钥不变，CA 侧不落盘新私钥
        persistFiles(serial, null, PemUtil.certToPem(cert));
        String algorithm = "EC".equals(cert.getPublicKey().getAlgorithm())
                ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048;
        CaCert row = buildRow(serial, old.getSubjectCn(), old.getCertType(), algorithm,
                caCert, cert, old.getSan(), old.getDescription(), username, tpl == null ? null : tpl.getId());
        certMapper.insert(row);
        auditService.record(username, "CERT_RENEW", String.valueOf(row.getId()),
                "更新证书：" + old.getSubjectCn() + "（原证书 id=" + id + " 已吊销 SUPERSEDED，新证 " + days + " 天）", ip);
        log.info("[证书更新] 新证 id={}, serial={}, 原证 id={} 已吊销", row.getId(), serial, id);
        return CertVo.from(row);
    }

    /**
     * 密钥更新 rekey（GM/T 0034 §12.2 密钥更新）：重新生成密钥对并签发新证书，原证书吊销。
     * 仅限内部生成密钥对的在线签发证书（私钥在 CA 侧）；CSR/公钥签发证书私钥由申请方自持，须重新走申请流程。
     */
    public CertVo rekey(Long id, Integer validityDays, String username, String ip) {
        CaCert old = findById(id);
        if (CaService.TYPE_ROOT.equals(old.getCertType()) || "SUB_CA".equals(old.getCertType())) {
            throw new IllegalArgumentException("根CA/二级CA不支持在线重签（CA 密钥轮换属系统维护，另走流程）");
        }
        if (!"VALID".equals(old.getStatus()) && !"FROZEN".equals(old.getStatus())) {
            throw new IllegalArgumentException("仅有效/挂起状态的证书可以重签（当前状态：" + old.getStatus() + "）");
        }
        if (!caService.issuedKeyExists(old.getSerialHex())) {
            throw new IllegalArgumentException("该证书私钥由申请方自持（CSR/公钥签发），CA 侧无法重签，请申请方重新走 RA 申请");
        }
        X509Certificate oldCert = PemUtil.parseCert(old.getCertPem());
        int days = resolveValidityDays(validityDays);
        CertTemplate tpl = old.getTemplateId() == null ? null : templateMapper.selectById(old.getTemplateId());
        String algorithm = old.getAlgorithm();

        KeyPair kp = CertUtil.generateKeyPair(algorithm);
        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        X509Certificate cert = tpl != null
                ? CertUtil.issueByTemplate(
                        new X500Name(oldCert.getSubjectX500Principal().getName()),
                        kp.getPublic(), caCert, caKey, old.getCertType(), days,
                        old.getSan(), crlUrl(), ocspUrl(),
                        tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issue(
                        new X500Name(oldCert.getSubjectX500Principal().getName()),
                        kp.getPublic(), caCert, caKey, old.getCertType(), days,
                        old.getSan(), crlUrl(), ocspUrl());

        markRevoked(old, "SUPERSEDED", username);
        String serial = cert.getSerialNumber().toString(16);
        persistFiles(serial, PemUtil.keyToPem(kp.getPrivate()), PemUtil.certToPem(cert));
        CaCert row = buildRow(serial, old.getSubjectCn(), old.getCertType(), algorithm,
                caCert, cert, old.getSan(), old.getDescription(), username, tpl == null ? null : tpl.getId());
        certMapper.insert(row);
        auditService.record(username, "CERT_REKEY", String.valueOf(row.getId()),
                "密钥更新：" + old.getSubjectCn() + "（原证书 id=" + id + " 已吊销，新密钥对 " + algorithm + "）", ip);
        log.info("[密钥更新] 新证 id={}, serial={}, 原证 id={} 已吊销", row.getId(), serial, id);
        return CertVo.from(row);
    }

    /** 吊销落库（供吊销/更新/重签共用，只改状态与原因，审计由调用方记录） */
    private void markRevoked(CaCert row, String reason, String username) {
        row.setStatus("REVOKED");
        row.setRevokeReason(reason == null || reason.isBlank() ? "UNSPECIFIED" : reason.trim());
        row.setRevokedAt(LocalDateTime.now());
        certMapper.updateById(row);
    }

    /** 过期归档扫描：把已超过有效期的有效证书标记 EXPIRED（幂等；签发记录与文件按策略长期留存） */
    public int expireScan() {
        List<CaCert> expired = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getStatus, "VALID")
                .lt(CaCert::getValidTo, LocalDateTime.now()));
        for (CaCert c : expired) {
            c.setStatus("EXPIRED");
            certMapper.updateById(c);
        }
        if (!expired.isEmpty()) {
            log.info("[归档] 已将 {} 张过期证书标记为 EXPIRED（留存策略见 docs/院内证书策略与KMC豁免声明）", expired.size());
        }
        return expired.size();
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

    /**
     * PKCS#12 软件证书导出（§5.3.3 介质导出）：证书链（叶子+签发SUB_CA+根）与私钥打包，
     * 口令加密保护（PBE）。仅限 CA 侧持私钥的终端证书（SERVER/CLIENT）；根/二级CA 为系统核心密钥不导出；
     * CSR/公钥签发的证书私钥由申请方自持，不在此列。
     */
    public byte[] exportP12(Long id, String password) {
        CaCert row = findById(id);
        if (CaService.TYPE_ROOT.equals(row.getCertType()) || CaService.TYPE_SUB_CA.equals(row.getCertType())) {
            throw new IllegalArgumentException("CA证书（根/二级CA）私钥为系统核心密钥，不提供PKCS#12导出");
        }
        if (password == null || password.length() < 4) {
            throw new IllegalArgumentException("导出口令不能少于4位");
        }
        if (!caService.issuedKeyExists(row.getSerialHex())) {
            throw new IllegalArgumentException("该证书无落盘私钥（CSR/公钥签发场景私钥由申请方自持，无CA侧密钥可导出）");
        }
        String keyPem = caService.loadIssuedKey(row.getSerialHex());
        if (keyPem == null) {
            throw new IllegalArgumentException("该证书私钥不可用（已封存或缺失）");
        }
        try {
            PrivateKey key = PemUtil.parsePrivateKey(keyPem);
            X509Certificate leaf = PemUtil.parseCert(row.getCertPem());
            List<X509Certificate> chain = new ArrayList<>();
            chain.add(leaf);
            CaCert sub = matchingSubCa(row);
            if (sub != null) {
                chain.add(PemUtil.parseCert(sub.getCertPem()));
            }
            chain.add(caService.loadRootCert());
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(row.getSerialHex(), key, password.toCharArray(),
                    chain.toArray(new X509Certificate[0]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ks.store(out, password.toCharArray());
            log.info("[导出] 证书 id={}（{}）已导出 PKCS#12，链长度={}", id, row.getSubjectCn(), chain.size());
            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#12导出失败: " + e.getMessage(), e);
        }
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
                            String description, String username, Long templateId) {
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
        row.setTemplateId(templateId);
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
