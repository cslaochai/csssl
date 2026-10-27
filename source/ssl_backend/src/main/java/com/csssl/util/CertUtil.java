package com.csssl.util;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.PolicyInformation;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.StringReader;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * X.509 证书签发核心：RSA2048 / SM2（国密）密钥生成、根CA自签、服务端/客户端证书签发、
 * CSR 签发、吊销列表（CRL）构建。全部走 BouncyCastle（bcprov + bcpkix）。
 */
public final class CertUtil {

    public static final String ALG_RSA2048 = "RSA2048";
    public static final String ALG_SM2 = "SM2";

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+$");

    private CertUtil() {
    }

    /** 生成密钥对：RSA2048 或 SM2（sm2p256v1 推荐曲线） */
    public static KeyPair generateKeyPair(String algorithm) {
        try {
            if (isSm2(algorithm)) {
                // 走 JCE + BC provider：BC 会把 sm2p256v1 曲线 OID（1.2.156.10197.1.301）编进 SPKI，
                // 产出的证书与 openssl / 国密工具链互通
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
                kpg.initialize(new java.security.spec.ECGenParameterSpec("sm2p256v1"), new SecureRandom());
                return kpg.generateKeyPair();
            }
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("密钥生成失败[" + algorithm + "]: " + e.getMessage(), e);
        }
    }

    /** 生成随机正整数序列号（16 字节） */
    public static BigInteger randomSerial() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        bytes[0] &= 0x7F;
        return new BigInteger(bytes);
    }

    /** 根CA自签证书 */
    public static X509Certificate selfSignRoot(KeyPair rootKey, String cn, String algorithm, int validityDays) {
        try {
            X500Name subject = new X500Name("CN=" + cn + ",O=CSSSL");
            BigInteger serial = randomSerial();
            Date notBefore = new Date();
            Date notAfter = Date.from(LocalDateTime.now().plusDays(validityDays)
                    .atZone(ZoneId.systemDefault()).toInstant());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, serial, notBefore, notAfter, subject, rootKey.getPublic());
            JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    utils.createSubjectKeyIdentifier(rootKey.getPublic()));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    utils.createAuthorityKeyIdentifier(rootKey.getPublic()));
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm(algorithm))
                    .setProvider("BC").build(rootKey.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        } catch (Exception e) {
            throw new IllegalStateException("根CA自签失败: " + e.getMessage(), e);
        }
    }

    /** 用签发CA签发服务端/客户端/二级CA证书（内部生成密钥对时调用） */
    public static X509Certificate issue(KeyPair subjectKey, X509Certificate caCert, PrivateKey caKey,
                                        String cn, String certType, int validityDays, String san,
                                        String crlUrl, String ocspUrl) {
        X500Name subject = new X500Name("CN=" + cn + ",O=CSSSL");
        return issue(subject, subjectKey.getPublic(), caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl);
    }

    /**
     * 按证书模板签发（GM/T 0034 §5.3.4 证书策略管理）：KeyUsage/EKU/策略OID/有效期全部取自模板，
     * 杜绝手工拼扩展项；certType 也以模板为准。
     */
    public static X509Certificate issueByTemplate(X500Name subject, PublicKey subjectPub,
                                                  X509Certificate caCert, PrivateKey caKey,
                                                  String certType, int validityDays, String san,
                                                  String crlUrl, String ocspUrl,
                                                  String keyUsage, String extKeyUsage, String policyOid) {
        KeyPurposeId eku = ekuPurpose(extKeyUsage);
        return issue(subject, subjectPub, caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl,
                keyUsageBits(keyUsage), eku, policyOid);
    }

    /** KeyUsage 字符串（逗号分隔）→ BC 位掩码 */
    public static int keyUsageBits(String keyUsage) {
        int bits = 0;
        if (keyUsage == null || keyUsage.isBlank()) {
            throw new IllegalArgumentException("证书模板必须配置 KeyUsage（如 digitalSignature,keyEncipherment）");
        }
        for (String item : keyUsage.split(",")) {
            switch (item.trim().toLowerCase()) {
                case "digitalsignature" -> bits |= KeyUsage.digitalSignature;
                case "nonrepudiation" -> bits |= KeyUsage.nonRepudiation;
                case "keyencipherment" -> bits |= KeyUsage.keyEncipherment;
                case "dataencipherment" -> bits |= KeyUsage.dataEncipherment;
                case "keyagreement" -> bits |= KeyUsage.keyAgreement;
                case "keycertsign" -> bits |= KeyUsage.keyCertSign;
                case "crlsign" -> bits |= KeyUsage.cRLSign;
                case "encipheronly" -> bits |= KeyUsage.encipherOnly;
                case "decipheronly" -> bits |= KeyUsage.decipherOnly;
                default -> throw new IllegalArgumentException("不支持的 KeyUsage 项：" + item.trim());
            }
        }
        if (bits == 0) {
            throw new IllegalArgumentException("KeyUsage 配置结果为空，请检查模板配置");
        }
        return bits;
    }

    /** EKU 字符串 → BC KeyPurposeId（空/null 返回 null 即不加 EKU 扩展） */
    public static KeyPurposeId ekuPurpose(String extKeyUsage) {
        if (extKeyUsage == null || extKeyUsage.isBlank()) {
            return null;
        }
        return switch (extKeyUsage.trim().toLowerCase()) {
            case "serverauth" -> KeyPurposeId.id_kp_serverAuth;
            case "clientauth" -> KeyPurposeId.id_kp_clientAuth;
            default -> throw new IllegalArgumentException("不支持的 EKU：" + extKeyUsage + "（仅支持 serverAuth / clientAuth）");
        };
    }

    /** 用签发CA签发证书（CSR 场景：主体与公钥取自 CSR） */
    public static X509Certificate issueFromCsr(String csrPem, X509Certificate caCert, PrivateKey caKey,
                                               String certType, int validityDays, String san,
                                               String crlUrl, String ocspUrl) {
        CsrSubject subject = parseCsr(csrPem);
        return issue(subject.subject(), subject.publicKey(), caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl);
    }

    /** CSR 签发（模板版）：KeyUsage/EKU/策略OID 显式传入（GM/T 0034 §5.3.4） */
    public static X509Certificate issueFromCsrByTemplate(String csrPem, X509Certificate caCert, PrivateKey caKey,
                                                         String certType, int validityDays, String san,
                                                         String crlUrl, String ocspUrl,
                                                         String keyUsage, String extKeyUsage, String policyOid) {
        CsrSubject subject = parseCsr(csrPem);
        return issue(subject.subject(), subject.publicKey(), caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl,
                keyUsageBits(keyUsage), ekuPurpose(extKeyUsage), policyOid);
    }

    /** 解析 CSR 提取主体与公钥 */
    private static CsrSubject parseCsr(String csrPem) {
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            Object obj = parser.readObject();
            if (!(obj instanceof PKCS10CertificationRequest csr)) {
                throw new IllegalArgumentException("CSR解析失败：不是 PKCS#10 证书请求");
            }
            PublicKey pub = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter()
                    .setProvider("BC").getPublicKey(csr.getSubjectPublicKeyInfo());
            return new CsrSubject(csr.getSubject(), pub);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CSR解析失败: " + e.getMessage(), e);
        }
    }

    /** CSR 主体记录：subject DN + 公钥 */
    private record CsrSubject(X500Name subject, PublicKey publicKey) {
    }

    /**
     * 用签发CA签发证书（RA 公钥申请场景：主体 DN 与公钥由申请方提供，私钥自持不落盘）。
     * subjectDn 为 RFC2253 格式（如 "CN=张三,O=医院,OU=门诊部"）。
     */
    public static X509Certificate issuePublicKey(String subjectDn, String publicKeyPem,
                                                 X509Certificate caCert, PrivateKey caKey,
                                                 String certType, int validityDays, String san,
                                                 String crlUrl, String ocspUrl) {
        try {
            PublicKey pub = PemUtil.parsePublicKey(publicKeyPem);
            X500Name subject = new X500Name(subjectDn);
            return issue(subject, pub, caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥/DN 解析失败: " + e.getMessage(), e);
        }
    }

    /** 公钥签发（模板版）：KeyUsage/EKU/策略OID 显式传入（GM/T 0034 §5.3.4） */
    public static X509Certificate issuePublicKeyByTemplate(String subjectDn, String publicKeyPem,
                                                           X509Certificate caCert, PrivateKey caKey,
                                                           String certType, int validityDays, String san,
                                                           String crlUrl, String ocspUrl,
                                                           String keyUsage, String extKeyUsage, String policyOid) {
        try {
            PublicKey pub = PemUtil.parsePublicKey(publicKeyPem);
            X500Name subject = new X500Name(subjectDn);
            return issue(subject, pub, caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl,
                    keyUsageBits(keyUsage), ekuPurpose(extKeyUsage), policyOid);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥/DN 解析失败: " + e.getMessage(), e);
        }
    }

    /** 签发通用入口：主体 X500Name + 公钥 + 吊销分发点(CDP)/OCSP访问点(AIA) + 模板化扩展项 */
    private static X509Certificate issue(X500Name subject, PublicKey subjectPub,
                                          X509Certificate caCert, PrivateKey caKey,
                                          String certType, int validityDays, String san,
                                          String crlUrl, String ocspUrl) {
        boolean isSubCa = "SUB_CA".equalsIgnoreCase(certType);
        int legacyBits = isSubCa
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : ("CLIENT".equalsIgnoreCase(certType)
                        ? KeyUsage.digitalSignature
                        : KeyUsage.digitalSignature | KeyUsage.keyEncipherment);
        KeyPurposeId legacyEku = isSubCa ? null
                : ("CLIENT".equalsIgnoreCase(certType)
                        ? KeyPurposeId.id_kp_clientAuth : KeyPurposeId.id_kp_serverAuth);
        return issue(subject, subjectPub, caCert, caKey, certType, validityDays, san, crlUrl, ocspUrl,
                legacyBits, legacyEku, null);
    }

    /** 签发通用入口（模板化版）：KeyUsage/EKU/策略OID 显式传入 */
    private static X509Certificate issue(X500Name subject, PublicKey subjectPub,
                                          X509Certificate caCert, PrivateKey caKey,
                                          String certType, int validityDays, String san,
                                          String crlUrl, String ocspUrl,
                                          int keyUsageBits, KeyPurposeId eku, String policyOid) {
        try {
            BigInteger serial = randomSerial();
            Date notBefore = new Date();
            Date notAfter = Date.from(LocalDateTime.now().plusDays(validityDays)
                    .atZone(ZoneId.systemDefault()).toInstant());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded()),
                    serial, notBefore, notAfter, subject, subjectPub);
            JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();

            boolean isSubCa = "SUB_CA".equalsIgnoreCase(certType);
            builder.addExtension(Extension.basicConstraints, true,
                    isSubCa ? new BasicConstraints(1) : new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));
            if (eku != null) {
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(eku));
            }
            if (policyOid != null && !policyOid.isBlank()) {
                builder.addExtension(Extension.certificatePolicies, false,
                        new CertificatePolicies(new PolicyInformation(
                                new org.bouncycastle.asn1.ASN1ObjectIdentifier(policyOid.trim()))));
            }
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    utils.createSubjectKeyIdentifier(subjectPub));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    utils.createAuthorityKeyIdentifier(caCert));

            List<GeneralName> names = parseSan(san);
            if (!names.isEmpty()) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(names.toArray(new GeneralName[0])));
            }

            // 吊销可发现性：CRL 分发点（CDP）与 OCSP 访问点（AIA），供依赖方自动发现吊销状态
            if (crlUrl != null && !crlUrl.isBlank()) {
                builder.addExtension(Extension.cRLDistributionPoints, false,
                        new CRLDistPoint(new DistributionPoint[]{
                                new DistributionPoint(new DistributionPointName(new GeneralNames(
                                        new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl))), null, null)}));
            }
            if (ocspUrl != null && !ocspUrl.isBlank()) {
                builder.addExtension(Extension.authorityInfoAccess, false,
                        new AuthorityInformationAccess(new AccessDescription(
                                AccessDescription.id_ad_ocsp,
                                new GeneralName(GeneralName.uniformResourceIdentifier, ocspUrl))));
            }

            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithmForKey(caKey))
                    .setProvider("BC").build(caKey);
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        } catch (Exception e) {
            throw new IllegalStateException("证书签发失败: " + e.getMessage(), e);
        }
    }

    /** 从 CSR 读取 SAN（若 CSR 带 subjectAlternativeName 扩展则提取，否则用传入 san） */
    public static String sanFromCsr(String csrPem, String fallback) {
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            Object obj = parser.readObject();
            if (obj instanceof PKCS10CertificationRequest csr) {
                GeneralNames gns = GeneralNames.fromExtensions(csr.getRequestedExtensions(),
                        Extension.subjectAlternativeName);
                if (gns != null) {
                    List<String> items = new ArrayList<>();
                    for (GeneralName gn : gns.getNames()) {
                        if (gn.getTagNo() == GeneralName.dNSName || gn.getTagNo() == GeneralName.iPAddress) {
                            items.add(gn.getName().toString());
                        }
                    }
                    if (!items.isEmpty()) {
                        return String.join(",", items);
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败则回退到调用方传入的 san
        }
        return fallback;
    }

    /** 构建吊销列表（CRL v2），由根CA私钥签名 */
    public static java.security.cert.X509CRL buildCrl(X509Certificate caCert, PrivateKey caKey,
                                                      List<CrlEntry> entries) {
        try {
            Date now = new Date();
            X509v2CRLBuilder builder = new X509v2CRLBuilder(
                    X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded()), now);
            builder.setNextUpdate(Date.from(LocalDateTime.now().plusDays(30)
                    .atZone(ZoneId.systemDefault()).toInstant()));
            for (CrlEntry entry : entries) {
                builder.addCRLEntry(new BigInteger(entry.serialHex(), 16), entry.revokedAt(), entry.reason());
            }
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithmForKey(caKey))
                    .setProvider("BC").build(caKey);
            return new JcaX509CRLConverter().setProvider("BC").getCRL(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("CRL生成失败: " + e.getMessage(), e);
        }
    }

    /** 吊销原因文本 → CRLReason 编码（默认 UNSPECIFIED） */
    public static int crlReasonCode(String reason) {
        if (reason == null) {
            return CRLReason.unspecified;
        }
        return switch (reason.trim().toUpperCase(Locale.ROOT)) {
            case "KEY_COMPROMISE" -> CRLReason.keyCompromise;
            case "CA_COMPROMISE" -> CRLReason.cACompromise;
            case "AFFILIATION_CHANGED" -> CRLReason.affiliationChanged;
            case "SUPERSEDED" -> CRLReason.superseded;
            case "CESSATION_OF_OPERATION" -> CRLReason.cessationOfOperation;
            case "CERTIFICATE_HOLD" -> CRLReason.certificateHold;
            case "PRIVILEGE_WITHDRAWN" -> CRLReason.privilegeWithdrawn;
            default -> CRLReason.unspecified;
        };
    }

    /** 签名算法：SM2 曲线用 SM3withSM2，其余用 SHA256withRSA */
    public static String signatureAlgorithm(String algorithm) {
        return isSm2(algorithm) ? "SM3withSM2" : "SHA256withRSA";
    }

    /**
     * 签名算法跟随签发者（CA）密钥类型：EC（含 SM2 国密）私钥用 SM3withSM2，RSA 用 SHA256withRSA。
     * 注意：主体证书算法（SM2/RSA）决定证书密钥类型，与签名算法相互独立——
     * RSA 根CA完全可以签发 SM2 主体的证书（PKIX 允许跨算法签发）。
     */
    public static String signatureAlgorithmForKey(PrivateKey key) {
        if (key != null && "EC".equals(key.getAlgorithm())) {
            return "SM3withSM2";
        }
        return "SHA256withRSA";
    }

    public static boolean isSm2(String algorithm) {
        return algorithm != null && ALG_SM2.equalsIgnoreCase(algorithm.trim());
    }

    /** 校验算法取值合法性 */
    public static void checkAlgorithm(String algorithm) {
        if (algorithm == null
                || (!ALG_RSA2048.equalsIgnoreCase(algorithm.trim()) && !ALG_SM2.equalsIgnoreCase(algorithm.trim()))) {
            throw new IllegalArgumentException("不支持的算法: " + algorithm + "（仅支持 RSA2048 / SM2）");
        }
    }

    /** 解析 SAN 文本（逗号分隔，支持 DNS 名与 IPv4/IPv6） */
    private static List<GeneralName> parseSan(String san) {
        List<GeneralName> names = new ArrayList<>();
        if (san == null || san.isBlank()) {
            return names;
        }
        for (String item : san.split(",")) {
            String name = item.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (IPV4.matcher(name).matches() || IPV6.matcher(name).matches()) {
                names.add(new GeneralName(GeneralName.iPAddress, name));
            } else {
                names.add(new GeneralName(GeneralName.dNSName, name));
            }
        }
        return names;
    }

    /** 从证书提取 CN */
    public static String subjectCn(X509Certificate cert) {
        X500Name name = X500Name.getInstance(cert.getSubjectX500Principal().getEncoded());
        var cn = name.getRDNs(BCStyle.CN);
        if (cn != null && cn.length > 0) {
            return cn[0].getFirst().getValue().toString();
        }
        return cert.getSubjectX500Principal().getName();
    }

    /** CRL 吊销条目 */
    public record CrlEntry(String serialHex, Date revokedAt, int reason) {
    }
}
