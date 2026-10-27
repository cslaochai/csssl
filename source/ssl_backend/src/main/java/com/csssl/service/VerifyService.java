package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.dto.VerifyRequest;
import com.csssl.dto.VerifyResult;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
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
 * 校验项：签发者=本站根CA → 签名有效 → 未过期 → 未吊销 → 用途匹配（TLS/SIGN）。
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
        r.setSubjectCn(extractCn(cert));
        r.setIssuerCn(cert.getIssuerX500Principal().getName());
        r.setAlgorithm(cert.getSigAlgName());
        r.setValidFrom(cert.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        r.setValidTo(cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());

        // 1. 签发者必须是本站根CA（按 RDN 集合比较，规避 X500Principal RFC2253 输出反转导致的判等失真）
        X500Name issuer = X500Name.getInstance(cert.getIssuerX500Principal().getEncoded());
        X500Name rootSubject = X500Name.getInstance(root.getSubjectX500Principal().getEncoded());
        if (!sameDn(issuer, rootSubject)) {
            fail(r, "证书签发者不是本站根CA（" + rootSubject + "）");
            return r;
        }
        // 2. 签名验证（SM3withSM2 / SHA256withRSA 由 BC provider 处理）
        try {
            cert.verify(root.getPublicKey());
        } catch (Exception e) {
            log.warn("[证书校验] 签名验证失败: {}", e.getMessage());
            fail(r, "证书签名验证失败");
            return r;
        }
        // 3. 有效期
        try {
            cert.checkValidity();
        } catch (Exception e) {
            fail(r, "证书已过期或未生效");
            return r;
        }
        // 4. 吊销状态
        CaCert row = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getSerialHex, cert.getSerialNumber().toString(16)));
        if (row != null && "REVOKED".equals(row.getStatus())) {
            fail(r, "证书已被吊销（原因：" + row.getRevokeReason() + "）");
            return r;
        }
        // 5. 用途匹配
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
        r.setMessage("校验通过：证书可信、未过期、未吊销");
        return r;
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

    private static String extractCn(X509Certificate cert) {
        return com.csssl.util.CertUtil.subjectCn(cert);
    }

    private static void fail(VerifyResult r, String message) {
        r.setValid(false);
        r.setMessage(message);
    }
}
