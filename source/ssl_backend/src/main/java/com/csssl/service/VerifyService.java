package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.dto.VerifyRequest;
import com.csssl.dto.VerifyResult;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * 证书链在线校验：业务系统（cslk / csmig）调用 /ca/public/verify 校验对方证书。
 * 支持多级链：叶子 → 服务签发二级CA（SUB_CA）→ 根CA。
 * 校验项：签发者=本站CA → 逐级验签 → 未过期 → 未吊销 → 用途匹配（TLS/SIGN）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyService {

    private final CaCertMapper certMapper;
    private final CaService caService;

    public VerifyResult verify(VerifyRequest req) {
        if (req.getCertPem() == null || req.getCertPem().isBlank()) {
            throw new IllegalArgumentException("待校验证书PEM不能为空");
        }
        X509Certificate cert = PemUtil.parseCert(req.getCertPem());
        X509Certificate root = caService.loadRootCert();

        VerifyResult r = new VerifyResult();
        r.setSerialHex(cert.getSerialNumber().toString(16));
        r.setSubjectCn(CertUtil.subjectCn(cert));
        r.setIssuerCn(cert.getIssuerX500Principal().getName());
        r.setAlgorithm(cert.getSigAlgName());
        r.setValidFrom(cert.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        r.setValidTo(cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());

        X500Name issuer = X500Name.getInstance(cert.getIssuerX500Principal().getEncoded());
        X500Name rootSubject = X500Name.getInstance(root.getSubjectX500Principal().getEncoded());

        if (sameDn(issuer, rootSubject)) {
            // 链①：叶子直接由根签发（根/早期证书场景）
            if (!verifySignedBy(cert, root, r, "根CA")) {
                return r;
            }
        } else {
            // 链②：叶子 → SUB_CA → 根（当前主流签发路径）
            CaCert subRow = findSubCaByIssuer(issuer);
            if (subRow == null) {
                fail(r, "证书签发者不是本站CA（" + cert.getIssuerX500Principal().getName() + "）");
                return r;
            }
            X509Certificate sub = PemUtil.parseCert(subRow.getCertPem());
            if (!"VALID".equals(subRow.getStatus())) {
                fail(r, "签发CA（" + subRow.getSubjectCn() + "）当前状态：" + subRow.getStatus() + "，证书链不可信");
                return r;
            }
            // SUB_CA 必须由根签发且签名有效
            X500Name subIssuer = X500Name.getInstance(sub.getIssuerX500Principal().getEncoded());
            if (!sameDn(subIssuer, rootSubject)) {
                fail(r, "签发CA（" + subRow.getSubjectCn() + "）不是本站根CA签发");
                return r;
            }
            if (!verifySignedBy(sub, root, r, "根CA")) {
                return r;
            }
            if (!verifySignedBy(cert, sub, r, "签发CA(" + subRow.getSubjectCn() + ")")) {
                return r;
            }
            r.setMessage("签发CA：" + subRow.getSubjectCn());
        }

        // 有效期
        try {
            cert.checkValidity();
        } catch (Exception e) {
            fail(r, "证书已过期或未生效");
            return r;
        }
        // 吊销状态
        CaCert row = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getSerialHex, cert.getSerialNumber().toString(16)));
        if (row != null && "REVOKED".equals(row.getStatus())) {
            fail(r, "证书已被吊销（原因：" + row.getRevokeReason() + "）");
            return r;
        }
        // 用途匹配
        String purpose = req.getPurpose() == null ? "" : req.getPurpose().trim().toUpperCase(Locale.ROOT);
        if ("TLS".equals(purpose) && !hasEku(cert, KeyPurposeId.id_kp_serverAuth)) {
            fail(r, "证书不具备服务端用途（serverAuth）");
            return r;
        }
        if ("SIGN".equals(purpose) && !hasEku(cert, KeyPurposeId.id_kp_clientAuth)) {
            fail(r, "证书不具备客户端用途（clientAuth）");
            return r;
        }

        r.setValid(true);
        String chainInfo = r.getMessage() == null ? "" : "，" + r.getMessage();
        r.setMessage("校验通过：证书可信、未过期、未吊销" + chainInfo);
        return r;
    }

    /** 验签：证书由签发者公钥签名（SM3withSM2 / SHA256withRSA 由 BC provider 处理） */
    private boolean verifySignedBy(X509Certificate cert, X509Certificate issuer, VerifyResult r, String issuerLabel) {
        try {
            cert.verify(issuer.getPublicKey(), "BC");
            return true;
        } catch (Exception e) {
            log.warn("[证书校验] {}验签失败: {}", issuerLabel, e.getMessage());
            fail(r, "证书签名验证失败（签发者" + issuerLabel + "）");
            return false;
        }
    }

    /** 按签发者 DN 查找本站签发的 SUB_CA（按 RDN 集合匹配） */
    private CaCert findSubCaByIssuer(X500Name issuer) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, CaService.TYPE_SUB_CA)
                .orderByDesc(CaCert::getCreatedAt));
        for (CaCert sub : subs) {
            X509Certificate subCert = PemUtil.parseCert(sub.getCertPem());
            if (sameDn(X500Name.getInstance(subCert.getSubjectX500Principal().getEncoded()), issuer)) {
                return sub;
            }
        }
        return null;
    }

    /** EKU 扩展缺失时不拦截（根CA/二级CA证书），存在时必须包含目标用途 */
    private static boolean hasEku(X509Certificate cert, KeyPurposeId purpose) {
        List<String> ekus;
        try {
            ekus = cert.getExtendedKeyUsage();
        } catch (Exception e) {
            return true;
        }
        if (ekus == null || ekus.isEmpty()) {
            return true;
        }
        return ekus.contains(purpose.getId());
    }

    /** 按 RDN 属性集合比较两个 DN（与顺序无关） */
    private static boolean sameDn(X500Name a, X500Name b) {
        return rdnSet(a).equals(rdnSet(b));
    }

    private static java.util.Set<String> rdnSet(X500Name name) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (org.bouncycastle.asn1.x500.RDN rdn : name.getRDNs()) {
            for (org.bouncycastle.asn1.x500.AttributeTypeAndValue av : rdn.getTypesAndValues()) {
                set.add(av.getType().getId() + "=" + av.getValue().toString());
            }
        }
        return set;
    }

    private static void fail(VerifyResult r, String message) {
        r.setValid(false);
        r.setMessage(message);
    }
}
