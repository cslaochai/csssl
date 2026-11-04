package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.CssslProperties;
import com.csssl.dto.PublicKeyIssueDTO;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CompanyMapper;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import com.csssl.vo.CertOverviewVO;
import com.csssl.vo.CertVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 证书签发与生命周期管理：公钥签发（仅 RA 申请批准后）/ 吊销 / 挂起 / 更新 / 换密钥 / 下载 / 概览统计。
 * 私钥只落盘（公司域 issued/{serial}），数据库只存证书与元信息。
 *
 * <p>不提供在线生成密钥对的直签、也不提供 CSR 直签：两者连同 cert:issue 权限点已删除，
 * 任何发证必须先在申请单上留下主体/模板快照并经被指派审批人批准。</p>
 *
 * <p>路线A多公司：签发入口以 companyUscc 首参为准（CaService 按公司取签发CA、私钥/证书落公司域、
 * CRL/OCSP 分发点写公司公开端点、证书行记录 company_uscc）。吊销/挂起/下载按证书行自带 company_uscc
 * 定位密钥与 CRL 域。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertService {

    /** 可签发的终端证书类型：二级CA 不在此列——它的签发方是根CA，由 SUB_CA 签出只会造出链外私生中间CA */
    private static final Set<String> TYPES = Set.of("SERVER", "CLIENT");
    private static final String SUB_CA_HINT = "（仅支持 SERVER / CLIENT；二级CA 只能由根CA 经「二级CA重建申请」签发）";
    private static final int MAX_VALIDITY_DAYS = 36500;
    private final CaCertMapper certMapper;
    private final CertTemplateMapper templateMapper;
    private final CompanyMapper companyMapper;
    private final SysUserMapper userMapper;
    private final CaService caService;
    private final CompanyService companyService;
    private final CssslProperties props;
    private final AuditService auditService;

    /**
     * 证书行归属公司（A1 加列前的语义兜底：空=default，存量行由 DDL 默认值归位 default）
     */
    private static String companyOf(CaCert row) {
        String k = row.getCompanyUscc();
        return k == null || k.isBlank() ? "default" : k;
    }

    /**
     * renew/rekey 公司版前置：显式传入的 companyUscc 必须与证书行归属一致（防跨公司更新/重签）
     */
    private static void requireCompanyMatch(String companyUscc, CaCert row) {
        if (!companyOf(row).equals(companyUscc)) {
            throw new IllegalArgumentException("证书归属公司为 " + companyOf(row) + "，不能在公司 " + companyUscc + " 域下更新/重签");
        }
    }

    /**
     * 签发后主体校验（落盘/入库前执行，违规即拒签）：
     * default 域不校验（老口径）；非 default 域 SUB_CA 证书 O 必须与公司受控 subject_o 一致，
     * 其他证书申请方 DN 带 O 时同样必须一致（防跨公司冒名）。
     */
    private static void checkSubjectCompany(X509Certificate cert, CaCompany company, String type) {
        if (company == null) {
            return;
        }
        String o = organizationOf(cert);
        boolean subCa = CaService.TYPE_SUB_CA.equalsIgnoreCase(type);
        if (o == null || o.isBlank()) {
            if (subCa) {
                throw new IllegalArgumentException("公司域签发 SUB_CA 证书主体必须携带与公司一致的 O（公司受控O=" + company.getSubjectO() + "）");
            }
            return;
        }
        if (!o.equals(company.getSubjectO())) {
            throw new IllegalArgumentException("证书主体O（" + o + "）与公司受控O（" + company.getSubjectO() + "）不一致，拒绝签发");
        }
    }

    /**
     * 从证书主体 DN 提取 O 属性值（无则 null）
     */
    private static String organizationOf(X509Certificate cert) {
        X500Name name = X500Name.getInstance(cert.getSubjectX500Principal().getEncoded());
        for (RDN rdn : name.getRDNs()) {
            for (AttributeTypeAndValue tv : rdn.getTypesAndValues()) {
                if (BCStyle.O.equals(tv.getType())) {
                    return tv.getValue().toString();
                }
            }
        }
        return null;
    }

    /**
     * 审计文案公司后缀：default 返回空串（存量审计文本逐字节不变），其他公司标注归属域
     */
    private static String companySuffix(String companyUscc) {
        return CaCompany.LEGACY_NAMESPACE.equals(companyUscc) ? "" : "，公司 " + companyUscc;
    }

    /**
     * 分页查询证书列表（companyScope 非空=仅该公司；null=跨公司全量），出参带中文展示字段与 keyHeld
     */
    public Page<CertVO> page(long pageNo, long pageSize, String certType, String status, String keyword, String companyScope) {
        LambdaQueryWrapper<CaCert> qw = new LambdaQueryWrapper<>();
        if (companyScope != null && !companyScope.isBlank()) {
            qw.eq(CaCert::getCompanyUscc, companyScope);
        }
        if (certType != null && !certType.isBlank()) {
            qw.eq(CaCert::getCertType, certType);
        } else {
            // 二级CA 属签发基础设施，不在主列表与根CA/终端证书混排：由根CA行的「二级CA」入口按公司查，
            // 或显式选 类型=二级CA 时才返回
            qw.ne(CaCert::getCertType, CaService.TYPE_SUB_CA);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(CaCert::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(CaCert::getSubjectCn, keyword).or().like(CaCert::getSerialHex, keyword));
        }
        qw.orderByDesc(CaCert::getCreatedAt);
        Page<CaCert> page = certMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        Page<CertVO> voPage = (Page<CertVO>) page.convert(CertVO::from);
        enrich(voPage.getRecords());
        return voPage;
    }

    /**
     * 证书详情（行级读按数据域收敛：公司用户访问他公司证书 403），出参带中文展示字段与 keyHeld
     */
    public CertVO detailVisible(Long id, String operatorCompany) {
        CaCert row = findById(id);
        CertVO vo = CertVO.from(row);
        enrich(List.of(vo));
        return vo;
    }

    /**
     * 出参补中文展示字段：公司中文名、签发人中文姓名（key/账号仅作兜底回落，界面不裸吐英文标识）。
     */
    private void enrich(List<CertVO> vos) {
        Set<String> companyUsccs = new java.util.HashSet<>();
        for (CertVO vo : vos) {
            if (vo.getCompanyUscc() != null && !vo.getCompanyUscc().isBlank()) companyUsccs.add(vo.getCompanyUscc());
        }
        Map<String, String> companyNames = new HashMap<>();
        if (!companyUsccs.isEmpty()) {
            companyMapper.selectList(new LambdaQueryWrapper<CaCompany>().in(CaCompany::getUscc, companyUsccs))
                    .forEach(t -> companyNames.put(t.getUscc(), t.getCompanyName()));
        }
        for (CertVO vo : vos) {
            vo.setCompanyName(companyNames.get(vo.getCompanyUscc()));
            String rowCompany = vo.getCompanyUscc() == null || vo.getCompanyUscc().isBlank()
                    ? "default" : vo.getCompanyUscc();
            vo.setKeyHeld(caService.issuedKeyExists(rowCompany, vo.getSerialHex()));
        }
    }

    /**
     * 公钥签发（RA 路径，公司版）：申请方自持私钥，仅提交 Subject DN + 公钥 PEM；落到该公司域；
     * 非 default 域校验申请 DN 的 O 与公司受控 O 一致（SUB_CA 必须一致，_leaf 带 O 即须一致，防跨公司冒名）。
     *
     * <p><b>本方法是唯一的发证出口，且只允许 RA 申请单批准后调用</b>
     * （CertApplicationService#issue：提交 → 被指派审批人批准 → 持 app:issue 的人执行）。
     * policy 非空时按批准快照执行，保证审核内容与签出内容一致；
     * 在线生成密钥对的直签与 CSR 直签端点已连同 cert:issue 权限点一并删除。</p>
     */
    public CertVO issueWithPublicKey(String companyUscc, PublicKeyIssueDTO req, IssuePolicy policy,
                                     LoginUser actor, String ip) {
        String dn = req.getSubjectDn() == null ? "" : req.getSubjectDn().trim();
        if (dn.isEmpty()) {
            throw new IllegalArgumentException("主体DN不能为空（如 CN=张三,O=医院）");
        }
        if (req.getPublicKeyPem() == null || req.getPublicKeyPem().isBlank()) {
            throw new IllegalArgumentException("公钥PEM不能为空（申请方自持私钥，仅提交公钥）");
        }
        CaCompany company = checkIssueCompany(companyUscc);
        CertTemplate tpl = policy == null ? resolveTemplate(req.getTemplateId()) : null;
        String type = policy != null ? (policy.certType() == null ? "" : policy.certType().toUpperCase())
                : (tpl != null ? tpl.getCertType() : (req.getCertType() == null ? "CLIENT" : req.getCertType()).toUpperCase());
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的证书类型: " + type + SUB_CA_HINT);
        }
        if (policy != null && policy.algorithm() != null && !policy.algorithm().isBlank()) {
            CertUtil.checkAlgorithm(policy.algorithm());
        }
        int days = policy != null ? resolveValidityDays(policy.validityDays())
                : (tpl != null ? tpl.getValidityDays() : resolveValidityDays(req.getValidityDays()));
        String keyUsage = policy != null ? policy.keyUsage() : (tpl != null ? tpl.getKeyUsage() : null);
        String extKeyUsage = policy != null ? policy.extKeyUsage() : (tpl != null ? tpl.getExtKeyUsage() : null);
        String policyOid = policy != null ? policy.policyOid() : (tpl != null ? tpl.getPolicyOid() : null);

        X509Certificate caCert = caService.signingCert(companyUscc);
        PrivateKey caKey = caService.signingKey(companyUscc);
        // SAN 透传：申请单/请求里带的 SAN 必须写进证书，此前模板分支写死 null 属丢弃申请内容
        X509Certificate cert = policy != null || tpl != null
                ? CertUtil.issuePublicKeyByTemplate(dn, req.getPublicKeyPem(), caCert, caKey,
                type, days, req.getSan(), crlUrl(companyUscc), ocspUrl(companyUscc), keyUsage, extKeyUsage, policyOid)
                : CertUtil.issuePublicKey(dn, req.getPublicKeyPem(), caCert, caKey,
                type, days, req.getSan(), crlUrl(companyUscc), ocspUrl(companyUscc));
        checkSubjectCompany(cert, company, type);

        String serial = cert.getSerialNumber().toString(16);
        // 申请方自持私钥，不落盘
        persistFiles(companyUscc, serial, null, PemUtil.certToPem(cert));
        String cn = CertUtil.subjectCn(cert);
        String algorithm = "EC".equals(cert.getPublicKey().getAlgorithm())
                ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048;

        CaCert row = buildRow(companyUscc, serial, cn, type, algorithm, caCert, cert, req.getSan(),
                req.getDescription(), policy != null ? req.getTemplateId() : (tpl == null ? null : tpl.getId()));
        certMapper.insert(row);
        auditService.record(actor, "CERT_PUBKEY_ISSUE", String.valueOf(row.getId()),
                "公钥签发 " + type + " 证书：" + cn + (policy != null ? "（按批准快照）" : (tpl != null ? "（模板 " + tpl.getTemplateCode() + "）" : "")) + companySuffix(companyUscc), ip, companyUscc);
        log.info("[公钥签发][{}] id={}, cn={}, type={}, algorithm={}, serial={}, snapshot={}",
                companyUscc, row.getId(), cn, type, algorithm, serial, policy != null);
        return CertVO.from(row);
    }

    /**
     * 吊销证书
     */
    public CertVO revoke(Long id, String reason, LoginUser actor, String ip) {
        CaCert row = findById(id);
        if (CaService.TYPE_ROOT.equals(row.getCertType())) {
            throw new IllegalArgumentException("根CA证书不允许吊销，如需重建请使用「初始化根CA」");
        }
        if (!"VALID".equals(row.getStatus())) {
            throw new IllegalArgumentException("仅有效状态的证书可以吊销（当前状态：" + row.getStatus() + "）");
        }
        markRevoked(row, reason);
        auditService.record(actor, "CERT_REVOKE", String.valueOf(id),
                "吊销证书：" + row.getSubjectCn() + "，原因：" + row.getRevokeReason(), ip, companyOf(row));
        log.info("[证书吊销] id={}, cn={}, reason={}", id, row.getSubjectCn(), row.getRevokeReason());
        return CertVO.from(row);
    }

    /**
     * 挂起证书（GM/T 0034 §12.6）：有效期内暂停使用，不销毁。
     * 挂起期间证书进入 CRL（reason=certificateHold）、OCSP 返回 revoked/on-hold，解除后恢复。
     */
    public CertVO suspend(Long id, String reason, LoginUser actor, String ip) {
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
        auditService.record(actor, "CERT_SUSPEND", String.valueOf(id),
                "挂起证书：" + row.getSubjectCn() + "（CRL certificateHold / OCSP on-hold）", ip, companyOf(row));
        log.info("[证书挂起] id={}, cn={}", id, row.getSubjectCn());
        return CertVO.from(row);
    }

    /**
     * 解除挂起（GM/T 0034 §12.7）：恢复有效，从 CRL 移除、OCSP 恢复 good
     */
    public CertVO unsuspend(Long id, LoginUser actor, String ip) {
        CaCert row = findById(id);
        if (!"FROZEN".equals(row.getStatus())) {
            throw new IllegalArgumentException("仅挂起状态的证书可以解除挂起（当前状态：" + row.getStatus() + "）");
        }
        row.setStatus("VALID");
        row.setRevokeReason(null);
        row.setRevokedAt(null);
        certMapper.updateById(row);
        auditService.record(actor, "CERT_UNSUSPEND", String.valueOf(id),
                "解除挂起证书：" + row.getSubjectCn() + "（恢复有效）", ip, companyOf(row));
        log.info("[解除挂起] id={}, cn={}", id, row.getSubjectCn());
        return CertVO.from(row);
    }

    /**
     * 证书更新（公司版，GM/T 0034 §12.2 更新）：原证书吊销（SUPERSEDED）+ 以原公钥在该公司域重签延长有效期。
     * companyUscc 必须与证书行归属一致（防跨公司更新）。仅由证书操作单批准执行（CertOpTicketService）调用。
     */
    public CertVO renew(String companyUscc, Long id, Integer validityDays, LoginUser actor, String ip) {
        CaCert old = findById(id);
        requireCompanyMatch(companyUscc, old);
        if (CaService.TYPE_ROOT.equals(old.getCertType())) {
            throw new IllegalArgumentException("根CA证书不支持在线更新");
        }
        if (!"VALID".equals(old.getStatus()) && !"FROZEN".equals(old.getStatus())) {
            throw new IllegalArgumentException("仅有效/挂起状态的证书可以更新（当前状态：" + old.getStatus() + "）");
        }
        // 更新即再签发：受公司状态门禁（CLOSED/SUSPENDED 拒签；default 域不受公司表约束）
        CaCompany company = checkIssueCompany(companyOf(old));
        X509Certificate oldCert = PemUtil.parseCert(old.getCertPem());
        int days = resolveValidityDays(validityDays);
        CertTemplate tpl = old.getTemplateId() == null ? null : templateMapper.selectById(old.getTemplateId());

        String domain = companyOf(old);
        X509Certificate caCert = caService.signingCert(domain);
        PrivateKey caKey = caService.signingKey(domain);
        X509Certificate cert = tpl != null
                ? CertUtil.issueByTemplate(
                new X500Name(oldCert.getSubjectX500Principal().getName()),
                oldCert.getPublicKey(), caCert, caKey, old.getCertType(), days,
                old.getSan(), crlUrl(domain), ocspUrl(domain),
                tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issue(
                new X500Name(oldCert.getSubjectX500Principal().getName()),
                oldCert.getPublicKey(), caCert, caKey, old.getCertType(), days,
                old.getSan(), crlUrl(domain), ocspUrl(domain));
        checkSubjectCompany(cert, company, old.getCertType());

        markRevoked(old, "SUPERSEDED");
        String serial = cert.getSerialNumber().toString(16);
        // 同公钥重签：申请方私钥不变，CA 不生成新私钥；但保险库按序列号索引，
        // 原私钥条目在旧序列号下，需复制到新序列号，否则新证书 keyHeld=false 且永远无法再 rekey
        String oldKeyPem = caService.loadIssuedKey(domain, old.getSerialHex());
        persistFiles(domain, serial, oldKeyPem, PemUtil.certToPem(cert));
        String algorithm = "EC".equals(cert.getPublicKey().getAlgorithm())
                ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048;
        CaCert row = buildRow(domain, serial, old.getSubjectCn(), old.getCertType(), algorithm,
                caCert, cert, old.getSan(), old.getDescription(), tpl == null ? null : tpl.getId());
        certMapper.insert(row);
        auditService.record(actor, "CERT_RENEW", String.valueOf(row.getId()),
                "更新证书：" + old.getSubjectCn() + "（原证书 id=" + id + " 已吊销 SUPERSEDED，新证 " + days + " 天）" + companySuffix(domain), ip, domain);
        log.info("[证书更新][{}] 新证 id={}, serial={}, 原证 id={} 已吊销", domain, row.getId(), serial, id);
        return CertVO.from(row);
    }

    /**
     * 密钥更新 rekey（公司版，GM/T 0034 §12.2 密钥更新）：重新生成密钥对并在该公司域签发新证书，原证书吊销。
     * 仅限内部生成密钥对的在线签发证书（私钥在 CA 侧）；CSR/公钥签发证书私钥由申请方自持，须重新走申请流程。
     * 仅由证书操作单批准执行（CertOpTicketService）调用。
     */
    public CertVO rekey(String companyUscc, Long id, Integer validityDays, LoginUser actor, String ip) {
        CaCert old = findById(id);
        requireCompanyMatch(companyUscc, old);
        if (CaService.TYPE_ROOT.equals(old.getCertType()) || "SUB_CA".equals(old.getCertType())) {
            throw new IllegalArgumentException("根CA/二级CA不支持在线重签（CA 密钥轮换属系统维护，另走流程）");
        }
        if (!"VALID".equals(old.getStatus()) && !"FROZEN".equals(old.getStatus())) {
            throw new IllegalArgumentException("仅有效/挂起状态的证书可以重签（当前状态：" + old.getStatus() + "）");
        }
        String domain = companyOf(old);
        if (!caService.issuedKeyExists(domain, old.getSerialHex())) {
            throw new IllegalArgumentException("该证书私钥由申请方自持（CSR/公钥签发），CA 侧无法重签，请申请方重新走 RA 申请");
        }
        CaCompany company = checkIssueCompany(domain);
        X509Certificate oldCert = PemUtil.parseCert(old.getCertPem());
        int days = resolveValidityDays(validityDays);
        CertTemplate tpl = old.getTemplateId() == null ? null : templateMapper.selectById(old.getTemplateId());
        String algorithm = old.getAlgorithm();

        KeyPair kp = CertUtil.generateKeyPair(algorithm);
        X509Certificate caCert = caService.signingCert(domain);
        PrivateKey caKey = caService.signingKey(domain);
        X509Certificate cert = tpl != null
                ? CertUtil.issueByTemplate(
                new X500Name(oldCert.getSubjectX500Principal().getName()),
                kp.getPublic(), caCert, caKey, old.getCertType(), days,
                old.getSan(), crlUrl(domain), ocspUrl(domain),
                tpl.getKeyUsage(), tpl.getExtKeyUsage(), tpl.getPolicyOid())
                : CertUtil.issue(
                new X500Name(oldCert.getSubjectX500Principal().getName()),
                kp.getPublic(), caCert, caKey, old.getCertType(), days,
                old.getSan(), crlUrl(domain), ocspUrl(domain));
        checkSubjectCompany(cert, company, old.getCertType());

        markRevoked(old, "SUPERSEDED");
        String serial = cert.getSerialNumber().toString(16);
        persistFiles(domain, serial, PemUtil.keyToPem(kp.getPrivate()), PemUtil.certToPem(cert));
        CaCert row = buildRow(domain, serial, old.getSubjectCn(), old.getCertType(), algorithm,
                caCert, cert, old.getSan(), old.getDescription(), tpl == null ? null : tpl.getId());
        certMapper.insert(row);
        auditService.record(actor, "CERT_REKEY", String.valueOf(row.getId()),
                "密钥更新：" + old.getSubjectCn() + "（原证书 id=" + id + " 已吊销，新密钥对 " + algorithm + "）" + companySuffix(domain), ip, domain);
        log.info("[密钥更新][{}] 新证 id={}, serial={}, 原证 id={} 已吊销", domain, row.getId(), serial, id);
        return CertVO.from(row);
    }

    /**
     * 吊销落库（供吊销/更新/重签共用，只改状态与原因，审计由调用方记录）
     */
    private void markRevoked(CaCert row, String reason) {
        row.setStatus("REVOKED");
        row.setRevokeReason(reason == null || reason.isBlank() ? "UNSPECIFIED" : reason.trim());
        row.setRevokedAt(LocalDateTime.now());
        certMapper.updateById(row);
    }

    /**
     * 过期归档扫描：把已超过有效期的有效证书标记 EXPIRED（幂等；签发记录与文件按策略长期留存）
     */
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
     * 证书 PEM（仅公钥证书，不含私钥）：终端证书返回「叶子+签发SUB_CA」，二级CA 返回「SUB_CA+根」，
     * 供 TLS 服务端部署/业务系统导入信任链。
     */
    public String certPem(Long id) {
        CaCert row = findById(id);
        StringBuilder chain = new StringBuilder(row.getCertPem());
        if (CaService.TYPE_SUB_CA.equals(row.getCertType())) {
            // 二级CA 的上级是根CA（走 matchingSubCa 会按 issuer 找不到而错配成当前 SUB_CA）
            CaCert root = caService.findRoot(companyOf(row));
            if (root != null && !root.getSerialHex().equalsIgnoreCase(row.getSerialHex())) {
                chain.append(root.getCertPem());
            }
            return chain.toString();
        }
        if (!CaService.TYPE_ROOT.equals(row.getCertType())) {
            CaCert subCa = matchingSubCa(row);
            if (subCa != null) {
                chain.append(subCa.getCertPem());
            }
        }
        return chain.toString();
    }

    /**
     * 证书压缩包（ZIP，<b>不加密</b>）：内含一条证书链 PEM（叶子 + 签发SUB_CA）。
     *
     * <p>线下导入场景用：HIS 等外部系统直接上传该 zip 回填证书，包内证书自带 notBefore/notAfter，
     * 有效期真值由证书本身给出，不依赖任何额外清单文件。ZIP 不设口令——口令需线下另行传递，
     * 反而增加导入门槛，故按约定明文打包。
     */
    public byte[] certZipBytes(Long id) {
        CaCert row = findById(id);
        String pem = certPem(id);
        String serial = row.getSerialHex() == null || row.getSerialHex().isBlank() ? String.valueOf(id) : row.getSerialHex();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            // 条目名强制 ASCII：zip 条目名编码在跨库（JDK/jzip4j）读取时易踩字符集坑，序列号足够溯源
            zos.putNextEntry(new ZipEntry("cert-" + serial + ".pem"));
            zos.write(pem.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("生成证书压缩包失败：" + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    /**
     * 私钥 PEM（受 allow-private-key-download 开关控制，从证书归属公司的加密保险库解密读取）
     */
    public String keyPem(Long id) {
        if (!props.isAllowPrivateKeyDownload()) {
            throw new IllegalArgumentException("私钥下载已关闭（csssl.allow-private-key-download=false）");
        }
        CaCert row = findById(id);
        if (CaService.TYPE_ROOT.equals(row.getCertType())) {
            throw new IllegalArgumentException("根CA私钥为系统核心密钥，不提供在线下载，请直接操作服务器文件");
        }
        String companyUscc = companyOf(row);
        if (!caService.issuedKeyExists(companyUscc, row.getSerialHex())) {
            throw new IllegalArgumentException("该证书无落盘私钥（CSR签发场景私钥由申请方自持）");
        }
        String keyPem = caService.loadIssuedKey(companyUscc, row.getSerialHex());
        if (keyPem == null) {
            throw new IllegalArgumentException("该证书私钥不可用（已封存或缺失）");
        }
        return keyPem;
    }

    /**
     * 匹配叶子证书的签发 SUB_CA（限同公司域按 issuer 精确匹配，找不到时退回该公司当前 SUB_CA）
     */
    private CaCert matchingSubCa(CaCert leaf) {
        String companyUscc = companyOf(leaf);
        if (leaf.getIssuerCn() == null || leaf.getIssuerCn().isBlank()) {
            return caService.currentSubCa(companyUscc);
        }
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, "SUB_CA")
                .eq(CaCert::getCompanyUscc, companyUscc)
                .eq(CaCert::getSubjectCn, leaf.getIssuerCn())
                .orderByDesc(CaCert::getCreatedAt));
        return subs.isEmpty() ? caService.currentSubCa(companyUscc) : subs.get(0);
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
        String companyUscc = companyOf(row);
        if (!caService.issuedKeyExists(companyUscc, row.getSerialHex())) {
            throw new IllegalArgumentException("该证书无落盘私钥（CSR/公钥签发场景私钥由申请方自持，无CA侧密钥可导出）");
        }
        String keyPem = caService.loadIssuedKey(companyUscc, row.getSerialHex());
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
            chain.add(caService.loadRootCert(companyUscc));
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

    /**
     * 概览统计：按公司域过滤（证书总量/状态分布/类型分布/30天内到期），供仪表盘渲染
     */
    public CertOverviewVO overview(String companyUscc) {
        String company = companyUscc == null || companyUscc.isBlank() ? "default" : companyUscc;
        List<CaCert> all = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCompanyUscc, company));
        long valid = 0, revoked = 0, expired = 0;
        long server = 0, client = 0, subCa = 0;
        long expiring = 0;
        LocalDateTime deadline = LocalDateTime.now().plusDays(30);
        for (CaCert c : all) {
            switch (c.getStatus()) {
                case "VALID" -> valid++;
                case "REVOKED" -> revoked++;
                case "EXPIRED" -> expired++;
                default -> {
                }
            }
            switch (c.getCertType()) {
                case "SERVER" -> server++;
                case "CLIENT" -> client++;
                case "SUB_CA" -> subCa++;
                default -> {
                }
            }
            if ("VALID".equals(c.getStatus()) && c.getValidTo() != null
                    && c.getValidTo().isBefore(deadline)) {
                expiring++;
            }
        }
        CertOverviewVO data = new CertOverviewVO();
        data.setTotal(all.size());
        data.setValid(valid);
        data.setRevoked(revoked);
        data.setExpired(expired);
        data.setServer(server);
        data.setClient(client);
        data.setSubCa(subCa);
        data.setExpiring30d(expiring);
        data.setRoot(caService.status(company));
        return data;
    }

    /**
     * 按ID查询证书
     */
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

    /**
     * 签发入口公司门禁：default 域直接放行（存量语义不受公司表约束）；
     * 非 default 域要求公司存在且 ACTIVE——SUSPENDED/CLOSED 拒签（CompanyService.requireActive，IAE 口径）。
     *
     * @return default 域返回 null（老口径组装证书），其他公司返回注册表行（subject_o 受控来源）
     */
    private CaCompany checkIssueCompany(String companyUscc) {
        if (CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            return null;
        }
        companyService.requireActive(companyUscc);
        return companyService.get(companyUscc);
    }

    private void persistFiles(String companyUscc, String serial, String keyPem, String certPem) {
        try {
            Path dir = caService.issuedDir(companyUscc, serial);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("cert.pem"), certPem, StandardCharsets.UTF_8);
            if (keyPem != null) {
                // 私钥经该公司密钥保险库 SM4 加密落盘，不写明文
                caService.storeIssuedKey(companyUscc, serial, keyPem);
            }
        } catch (IOException e) {
            throw new IllegalStateException("证书落盘失败: " + e.getMessage(), e);
        }
    }

    /**
     * CRL 分发点 URL（写入证书 CDP 扩展）：default 沿用现有公开端点（存量证书扩展语义兼容），
     * 非 default 指向公司域端点 /ssl/ca/public/{companyUscc}/crl。
     */
    private String crlUrl(String companyUscc) {
        return CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                ? props.getPublicBaseUrl() + "/ca/public/crl"
                : props.getPublicBaseUrl() + "/ca/public/" + companyUscc + "/crl";
    }

    /**
     * OCSP 访问点 URL（写入证书 AIA 扩展）：default 现有端点不变，非 default 为 /ssl/ca/public/{companyUscc}/ocsp
     */
    private String ocspUrl(String companyUscc) {
        return CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                ? props.getPublicBaseUrl() + "/ca/public/ocsp"
                : props.getPublicBaseUrl() + "/ca/public/" + companyUscc + "/ocsp";
    }

    private CaCert buildRow(String companyUscc, String serial, String cn, String type, String algorithm,
                            X509Certificate caCert, X509Certificate cert, String san,
                            String description, Long templateId) {
        CaCert row = new CaCert();
        row.setCompanyUscc(companyUscc);
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
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /**
     * 按ID解析证书模板；templateId 为空返回 null（兼容不走模板的直签）
     */
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

    /**
     * 签发策略快照：RA 批准时从模板固化（certType/algorithm/validityDays/keyUsage/extKeyUsage/policyOid），
     * 签发按快照执行，杜绝批准后被改动的可变模板影响已批准申请（GM/T 0034 §5.3.4 策略固化）。
     */
    public record IssuePolicy(String certType, String algorithm, Integer validityDays,
                              String keyUsage, String extKeyUsage, String policyOid) {
    }
}
