package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.dto.VerifyQueryDTO;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCompany;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import com.csssl.vo.VerifyResultVO;
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
    /**
     * 校验项：签发者=本站CA → 逐级验签 → 未吊销 → 未过期 → 用途匹配（TLS/SIGN）。
     *
     * <p>路线A多公司：链验证沿指定公司根域（loadRootCert(companyUscc)，SUB_CA/吊销查询均限本域）；
     * 老端点（无 companyUscc）委托 default 域，行为不变。公司间互不信任——他域证书在本域校验按
     * "签发者不是本站CA" 失败。SUSPENDED/CLOSED 公司的历史证书必须仍可沿其链验证（合规点），
     * 本服务不做公司状态门禁。</p>
 *
     * <p>返回三态 status（与 OCSP RFC 6960 同口径，由调用方直接展示，不再自行推断）：
     * good=本域签发且未被吊销；revoked=已吊销或已挂起；unknown=非本域签发（序列号未命中账内）。
     * 注意 OCSP 三态不含时效——过期证书仍回 good，过期与否由 valid 字段承载。</p>
     */
    @Slf4j
    @Service
    @RequiredArgsConstructor
    public class VerifyService {

    private final CaCertMapper certMapper;
    private final CaService caService;

    /**
     * EKU 扩展缺失时不拦截（根CA/二级CA证书），存在时必须包含目标用途
     */
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

    /**
     * 用途口径归一：前端下拉传 serverAuth/clientAuth，对接手册传 TLS/SIGN，两种都认。
     * 归一后返回 "serverAuth" / "clientAuth" / null（不限用途）。
     */
    private static String normalizePurpose(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String p = raw.trim().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "TLS", "SERVERAUTH", "SERVER_AUTH" -> "serverAuth";
            case "SIGN", "CLIENTAUTH", "CLIENT_AUTH" -> "clientAuth";
            default -> null;
        };
    }

    /**
     * 按 RDN 属性集合比较两个 DN（与顺序无关）
     */
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

    private static void fail(VerifyResultVO r, String message) {
        r.setValid(false);
        r.setMessage(message);
    }

    /**
     * 校验（公司域版）：沿该公司 根CA ← SUB_CA ← 叶子 链验证
     */
    public VerifyResultVO verify(VerifyQueryDTO req, String companyUscc) {
        if (req.getCertPem() == null || req.getCertPem().isBlank()) {
            throw new IllegalArgumentException("待校验证书PEM不能为空");
        }
        X509Certificate cert = PemUtil.parseCert(req.getCertPem());
        X509Certificate root = caService.loadRootCert(companyUscc);

        VerifyResultVO r = new VerifyResultVO();
        r.setSerialHex(cert.getSerialNumber().toString(16));
        r.setSubjectCn(CertUtil.subjectCn(cert));
        r.setIssuerCn(cert.getIssuerX500Principal().getName());
        r.setAlgorithm(cert.getSigAlgName());
        r.setValidFrom(cert.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        r.setValidTo(cert.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());

        // 账内行（本公司域；serial 全局唯一，域过滤兜住跨域撞号）——三态判定的依据
        CaCert row = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getSerialHex, cert.getSerialNumber().toString(16))
                .eq(CaCert::getCompanyUscc, companyUscc));
        applyStatus(r, row);

        X500Name issuer = X500Name.getInstance(cert.getIssuerX500Principal().getEncoded());
        X500Name rootSubject = X500Name.getInstance(root.getSubjectX500Principal().getEncoded());

        if (sameDn(issuer, rootSubject)) {
            // 链①：叶子直接由根签发（根/早期证书场景）
            if (!verifySignedBy(cert, root, r, "根CA")) {
                return r;
            }
        } else {
            // 链②：叶子 → SUB_CA → 根（当前主流签发路径；SUB_CA 限定本公司域）
            CaCert subRow = findSubCaByIssuer(issuer, companyUscc);
            if (subRow == null) {
                // 签发者不是本公司任何 CA → 三态按 OCSP 口径归 unknown
                r.setStatus("unknown");
                r.setStatusText("签发者不是本公司签发");
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
        // 吊销状态（挂起 FROZEN 与吊销同口径：都不再可用，对齐 OCSP/CRL certificateHold）
        if (row != null && ("REVOKED".equals(row.getStatus()) || "FROZEN".equals(row.getStatus()))) {
            fail(r, "FROZEN".equals(row.getStatus())
                    ? "证书已挂起（原因：" + row.getRevokeReason() + "）"
                    : "证书已被吊销（原因：" + row.getRevokeReason() + "）");
            return r;
        }
        // 用途匹配（purpose 口径已归一：serverAuth/clientAuth，兼容对接手册的 TLS/SIGN）
        String purpose = normalizePurpose(req.getPurpose());
        if ("serverAuth".equals(purpose) && !hasEku(cert, KeyPurposeId.id_kp_serverAuth)) {
            fail(r, "证书不具备服务端用途（serverAuth）");
            return r;
        }
        if ("clientAuth".equals(purpose) && !hasEku(cert, KeyPurposeId.id_kp_clientAuth)) {
            fail(r, "证书不具备客户端/签名用途（clientAuth）");
            return r;
        }

        r.setValid(true);
        String chainInfo = r.getMessage() == null ? "" : "，" + r.getMessage();
        r.setMessage("校验通过：证书可信、未过期、未吊销" + chainInfo);
        return r;
    }

    /**
     * 按账内行填三态（对齐 OCSP RFC 6960，只管吊销维度；过期与否由 valid 承载）：
     * revoked=已吊销或已挂起；unknown=本域账内无此序列号；good=在册且未被吊销。
     */
    private void applyStatus(VerifyResultVO r, CaCert row) {
        if (row == null) {
            r.setStatus("unknown");
            r.setStatusText("不在本公司签发范围或序列号未命中");
        } else if ("REVOKED".equals(row.getStatus())) {
            r.setStatus("revoked");
            r.setStatusText("已吊销");
        } else if ("FROZEN".equals(row.getStatus())) {
            r.setStatus("revoked");
            r.setStatusText("已挂起");
        } else {
            r.setStatus("good");
            r.setStatusText("有效");
        }
    }

    /**
     * 验签：证书由签发者公钥签名（SM3withSM2 / SHA256withRSA 由 BC provider 处理）
     */
    private boolean verifySignedBy(X509Certificate cert, X509Certificate issuer, VerifyResultVO r, String issuerLabel) {
        try {
            cert.verify(issuer.getPublicKey(), "BC");
            return true;
        } catch (Exception e) {
            log.warn("[证书校验] {}验签失败: {}", issuerLabel, e.getMessage());
            fail(r, "证书签名验证失败（签发者" + issuerLabel + "）");
            return false;
        }
    }

    /**
     * 按签发者 DN 查找指定公司域的 SUB_CA（按 RDN 集合匹配；公司间互不代验）
     */
    private CaCert findSubCaByIssuer(X500Name issuer, String companyUscc) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, CaService.TYPE_SUB_CA)
                .eq(CaCert::getCompanyUscc, companyUscc)
                .orderByDesc(CaCert::getCreatedAt));
        for (CaCert sub : subs) {
            X509Certificate subCert = PemUtil.parseCert(sub.getCertPem());
            if (sameDn(X500Name.getInstance(subCert.getSubjectX500Principal().getEncoded()), issuer)) {
                return sub;
            }
        }
        return null;
    }
}
