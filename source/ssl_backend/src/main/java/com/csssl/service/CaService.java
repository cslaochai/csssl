package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.dto.CaStatusVo;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.KeyVault;
import com.csssl.util.PemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CA 生命周期管理（对齐国内持证CA通行架构：离线根CA → 在线服务签发二级CA）：
 * <ul>
 *   <li>根CA（ROOT）：仅用于初始化与签发 SUB_CA；初始化完成后私钥可「封存」离线，
 *       日常签发与 CRL 全部由二级CA（SUB_CA）完成，根私钥不再参与在线操作。</li>
 *   <li>SUB_CA（服务签发二级CA）：由根签发，承载在线签发/CRL/OCSP 签名；到期前自动轮换。</li>
 *   <li>私钥一律经 {@link KeyVault} SM4 加密落盘（key.pem.enc），数据库只存证书与元信息。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaService implements ApplicationRunner {

    public static final String TYPE_ROOT = "ROOT";
    public static final String TYPE_SUB_CA = "SUB_CA";

    /** 根私钥封存状态 */
    public static final String ROOT_KEY_ONLINE = "ONLINE";
    public static final String ROOT_KEY_SEALED = "SEALED";
    public static final String ROOT_KEY_MISSING = "MISSING";

    private final CssslProperties props;
    private final CaCertMapper certMapper;

    private volatile KeyVault vault;

    private KeyVault vault() {
        if (vault == null) {
            synchronized (this) {
                if (vault == null) {
                    vault = new KeyVault(Path.of(props.getStoreDir()), resolveMasterKey());
                }
            }
        }
        return vault;
    }

    /** 主密钥解析：优先配置/环境变量 SSL_MASTER_KEY，否则首启生成随机密钥落盘 store-dir/master.key */
    private String resolveMasterKey() {
        String configured = props.getKeyVault().getMasterKey();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        Path mkFile = Path.of(props.getStoreDir()).resolve("master.key");
        try {
            if (Files.exists(mkFile)) {
                return Files.readString(mkFile, StandardCharsets.UTF_8).trim();
            }
            Files.createDirectories(mkFile.getParent());
            String generated = KeyVault.generateMasterKeyHex();
            Files.writeString(mkFile, generated, StandardCharsets.UTF_8);
            log.warn("[密钥保险库] 未配置 SSL_MASTER_KEY，已生成随机主密钥落盘 {}（生产环境必须通过环境变量注入）", mkFile);
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("主密钥初始化失败: " + e.getMessage(), e);
        }
    }

    private Path rootDir() {
        return Path.of(props.getStoreDir(), "root");
    }

    private Path rootCertFile() {
        return rootDir().resolve("cert.pem");
    }

    private Path subCaDir() {
        return Path.of(props.getStoreDir(), "subca");
    }

    private Path subCaCertFile() {
        return subCaDir().resolve("cert.pem");
    }

    /** 签发证书落盘目录（issued/{serial}） */
    public Path issuedDir(String serialHex) {
        return Path.of(props.getStoreDir(), "issued", serialHex);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            CaCert root = ensureRoot();
            ensureSubCa();
            log.info("[CA] 启动完成：root={}({}), subCa 签发就绪", root.getSubjectCn(), root.getAlgorithm());
        } catch (Exception e) {
            log.error("[CA] 启动初始化失败，请检查 store-dir 权限、主密钥与数据库连接: {}", e.getMessage());
        }
    }

    // ==================== 根CA ====================

    /**
     * 确保根CA存在（幂等）：优先文件态 → 重建记录 → 全新生成。
     */
    public synchronized CaCert ensureRoot() {
        CaCert root = findRoot();

        if (root != null && vault().keyExists("root", "key")) {
            markExpiredIfNeeded(root);
            return root;
        }
        if (root != null && !vault().keyExists("root", "key")) {
            throw new IllegalStateException("根CA私钥缺失（" + rootDir() + "），无法签发二级CA，请恢复密钥或重建根CA");
        }
        if (root == null && Files.exists(rootCertFile()) && vault().keyExists("root", "key")) {
            CaCert rebuilt = rebuildFromFiles();
            log.warn("[根CA] 数据库记录缺失，已从落盘文件重建（serial={}）", rebuilt.getSerialHex());
            return rebuilt;
        }
        return generateRoot();
    }

    /** 全新生成根CA（管理端 /admin/ca/init 与启动初始化共用） */
    private CaCert generateRoot() {
        CssslProperties.RootCa rootProps = props.getRootCa();
        String algorithm = rootProps.getAlgorithm() == null || rootProps.getAlgorithm().isBlank()
                ? CertUtil.ALG_RSA2048 : rootProps.getAlgorithm().trim();
        CertUtil.checkAlgorithm(algorithm);

        KeyPair keyPair = CertUtil.generateKeyPair(algorithm);
        X509Certificate cert = CertUtil.selfSignRoot(keyPair, rootProps.getCn(),
                algorithm, rootProps.getValidityDays());
        String serial = cert.getSerialNumber().toString(16);

        try {
            Files.createDirectories(rootDir());
            Files.writeString(rootCertFile(), PemUtil.certToPem(cert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("根CA落盘失败: " + e.getMessage(), e);
        }
        vault().storeKey("root", "key", PemUtil.keyToPem(keyPair.getPrivate()));

        CaCert row = new CaCert();
        row.setSerialHex(serial);
        row.setSubjectCn(CertUtil.subjectCn(cert));
        row.setCertType(TYPE_ROOT);
        row.setAlgorithm(algorithm);
        row.setIssuerCn(CertUtil.subjectCn(cert));
        row.setValidFrom(LocalDateTime.now());
        row.setValidTo(cert.getNotAfter().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        row.setStatus("VALID");
        row.setCertPem(PemUtil.certToPem(cert));
        row.setDescription("系统根CA（首次启动自动初始化）");
        row.setCreatedBy("SYSTEM");
        row.setCreatedAt(LocalDateTime.now());
        certMapper.insert(row);

        log.info("[根CA] 初始化完成：cn={}, algorithm={}, serial={}", row.getSubjectCn(), algorithm, serial);
        return row;
    }

    /** 数据库记录缺失但落盘文件存在时，从文件重建记录 */
    private CaCert rebuildFromFiles() {
        try {
            String certPem = Files.readString(rootCertFile(), StandardCharsets.UTF_8);
            X509Certificate cert = PemUtil.parseCert(certPem);
            CaCert row = new CaCert();
            row.setSerialHex(cert.getSerialNumber().toString(16));
            row.setSubjectCn(CertUtil.subjectCn(cert));
            row.setCertType(TYPE_ROOT);
            row.setAlgorithm(CertUtil.isSm2(props.getRootCa().getAlgorithm())
                    ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048);
            row.setIssuerCn(CertUtil.subjectCn(cert));
            row.setValidFrom(LocalDateTime.now());
            row.setValidTo(cert.getNotAfter().toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            row.setStatus("VALID");
            row.setCertPem(certPem);
            row.setDescription("系统根CA（从落盘文件恢复）");
            row.setCreatedBy("SYSTEM");
            row.setCreatedAt(LocalDateTime.now());
            certMapper.insert(row);
            return row;
        } catch (IOException e) {
            throw new IllegalStateException("根CA文件恢复失败: " + e.getMessage(), e);
        }
    }

    /** 查询根CA记录 */
    public CaCert findRoot() {
        List<CaCert> roots = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_ROOT)
                .orderByDesc(CaCert::getCreatedAt));
        return roots.isEmpty() ? null : roots.get(0);
    }

    /** 加载根证书 */
    public X509Certificate loadRootCert() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /** 加载根CA私钥（仅用于签发 SUB_CA；已封存则报错） */
    public PrivateKey loadRootKey() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        String keyPem = vault().loadKey("root", "key");
        if (keyPem == null) {
            throw new IllegalStateException("根CA私钥缺失，请恢复密钥或重建根CA");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    /** 根私钥状态：ONLINE / SEALED / MISSING */
    public String rootKeyState() {
        if (vault().isSealed("root", "key")) {
            return ROOT_KEY_SEALED;
        }
        return vault().keyExists("root", "key") ? ROOT_KEY_ONLINE : ROOT_KEY_MISSING;
    }

    /** 封存根私钥：进入离线态，此后仅 SUB_CA 承担在线签发（幂等） */
    public synchronized void sealRootKey() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法封存");
        }
        ensureSubCa();
        vault().sealKey("root", "key");
        log.info("[根CA] 根私钥已封存离线（serial={}），日常签发由 SUB_CA 承担", root.getSerialHex());
    }

    /** 解封根私钥：恢复在线态（幂等） */
    public synchronized void unsealRootKey() {
        if (findRoot() == null) {
            throw new IllegalStateException("根CA未初始化，无法解封");
        }
        vault().unsealKey("root", "key");
        if (vault().isSealed("root", "key")) {
            throw new IllegalStateException("根私钥文件缺失（已封存），无法解封");
        }
        log.info("[根CA] 根私钥已解封，恢复在线态");
    }

    /** 管理端主动初始化：已就绪则原样返回；根私钥丢失则吊销旧根并重建（连带吊销旧SUB_CA） */
    public synchronized CaStatusVo initRoot() {
        CaCert root = findRoot();
        if (root != null && vault().keyExists("root", "key")) {
            return status();
        }
        if (root != null) {
            revokeAllSubCa("ROOT_KEY_LOST", root.getSubjectCn());
            root.setStatus("REVOKED");
            root.setRevokeReason("ROOT_KEY_LOST");
            root.setRevokedAt(LocalDateTime.now());
            certMapper.updateById(root);
            log.warn("[根CA] 旧根私钥缺失，已吊销（serial={}）并重建新根", root.getSerialHex());
        }
        CaCert created = generateRoot();
        ensureSubCa();
        CaStatusVo vo = status();
        vo.setMessage("根CA已重建：" + created.getSubjectCn() + "（序列号 " + created.getSerialHex() + "），并签发新SUB_CA");
        return vo;
    }

    /** 吊销全部有效 SUB_CA（根重建/根丢失时调用，旧链整体失效） */
    private void revokeAllSubCa(String reason, String rootCn) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA));
        for (CaCert sub : subs) {
            if (!"REVOKED".equals(sub.getStatus())) {
                sub.setStatus("REVOKED");
                sub.setRevokeReason(reason);
                sub.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(sub);
            }
        }
    }

    // ==================== SUB_CA（服务签发二级CA） ====================

    /** 确保有效 SUB_CA 存在（幂等）：无有效 SUB_CA 时用根签发；临近到期自动轮换 */
    public synchronized CaCert ensureSubCa() {
        CaCert sub = findValidSubCa();
        if (sub != null && vault().keyExists("subca", "key")) {
            return sub;
        }
        // 根私钥不可用时不允许新建/轮换 SUB_CA
        if (ROOT_KEY_SEALED.equals(rootKeyState())) {
            throw new IllegalStateException("根CA私钥已封存离线且无有效 SUB_CA，请先解封根密钥后轮换签发");
        }
        X509Certificate rootCert = loadRootCert();
        PrivateKey rootKey = loadRootKey();

        CssslProperties.SubCa subProps = props.getSubCa();
        // SUB_CA 算法跟随根（RSA根→RSA SUB_CA，国密根→SM2 SUB_CA）
        String algorithm = findRoot().getAlgorithm();
        KeyPair subKey = CertUtil.generateKeyPair(algorithm);
        X509Certificate subCert = CertUtil.issue(subKey, rootCert, rootKey,
                subProps.getCn(), TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);

        String serial = subCert.getSerialNumber().toString(16);
        try {
            Files.createDirectories(subCaDir());
            Files.writeString(subCaCertFile(), PemUtil.certToPem(subCert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("SUB_CA落盘失败: " + e.getMessage(), e);
        }
        vault().storeKey("subca", "key", PemUtil.keyToPem(subKey.getPrivate()));

        // 旧 SUB_CA 标记 EXPIRED（由新 SUB_CA 接管在线签发）
        List<CaCert> oldSubs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA).eq(CaCert::getStatus, "VALID"));
        for (CaCert old : oldSubs) {
            if (!old.getSerialHex().equalsIgnoreCase(serial)) {
                old.setStatus("EXPIRED");
                certMapper.updateById(old);
            }
        }

        CaCert row = new CaCert();
        row.setSerialHex(serial);
        row.setSubjectCn(CertUtil.subjectCn(subCert));
        row.setCertType(TYPE_SUB_CA);
        row.setAlgorithm(algorithm);
        row.setIssuerCn(CertUtil.subjectCn(rootCert));
        row.setValidFrom(LocalDateTime.now());
        row.setValidTo(subCert.getNotAfter().toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        row.setStatus("VALID");
        row.setCertPem(PemUtil.certToPem(subCert));
        row.setDescription("服务签发二级CA（根签发，承担在线签发/CRL/OCSP）");
        row.setCreatedBy("SYSTEM");
        row.setCreatedAt(LocalDateTime.now());
        certMapper.insert(row);
        log.info("[SUB_CA] 已用根签发二级CA：cn={}, algorithm={}, serial={}, 有效期{}天",
                row.getSubjectCn(), algorithm, serial, subProps.getValidityDays());
        return row;
    }

    /** 查询当前有效 SUB_CA（未过期且距到期 > renewBeforeDays） */
    public CaCert findValidSubCa() {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getStatus, "VALID")
                .orderByDesc(CaCert::getCreatedAt));
        if (subs.isEmpty()) {
            return null;
        }
        CaCert sub = subs.get(0);
        // 临近到期（< renewBeforeDays）视为不可继续签发，由 ensureSubCa 轮换
        LocalDateTime renewAt = sub.getValidTo() == null ? null
                : sub.getValidTo().minusDays(props.getSubCa().getRenewBeforeDays());
        if (renewAt != null && renewAt.isBefore(LocalDateTime.now())) {
            return null;
        }
        return sub;
    }

    /** 当前签发CA（SUB_CA）：在线签发/CRL/OCSP 的证书与私钥 */
    public X509Certificate signingCert() {
        CaCert sub = ensureSubCa();
        return PemUtil.parseCert(sub.getCertPem());
    }

    public PrivateKey signingKey() {
        CaCert sub = ensureSubCa();
        String keyPem = vault().loadKey("subca", "key");
        if (keyPem == null) {
            throw new IllegalStateException("SUB_CA私钥缺失，请重建 SUB_CA");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    /** 当前有效 SUB_CA 记录（供链组装/OCSP 使用；无则返回 null） */
    public CaCert currentSubCa() {
        CaCert sub = findValidSubCa();
        return sub != null ? sub : certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .orderByDesc(CaCert::getCreatedAt)
                .last("limit 1"));
    }

    // ==================== 状态/导出/签发密钥存取 ====================

    /** 根CA状态（不抛异常，返回未初始化原因供页面展示；含 SUB_CA 与根密钥封存状态） */
    public CaStatusVo status() {
        CaStatusVo vo = new CaStatusVo();
        CaCert root = findRoot();
        if (root == null) {
            vo.setInitialized(false);
            if (Files.exists(rootCertFile()) || vault().keyExists("root", "key")) {
                vo.setMessage("证书文件存在但数据库记录缺失，调用初始化接口可自动恢复");
            } else {
                vo.setMessage("根CA未初始化，首次启动或点击「初始化根CA」自动生成");
            }
            return vo;
        }
        vo.setCn(root.getSubjectCn());
        vo.setAlgorithm(root.getAlgorithm());
        vo.setSerialHex(root.getSerialHex());
        vo.setValidFrom(root.getValidFrom());
        vo.setValidTo(root.getValidTo());
        vo.setStatus(root.getStatus());
        vo.setRootKeyState(rootKeyState());
        if (ROOT_KEY_MISSING.equals(rootKeyState())) {
            vo.setInitialized(false);
            vo.setMessage("根CA私钥缺失，无法签发二级CA，请恢复密钥或重建根CA");
        } else {
            vo.setInitialized(true);
        }
        CaCert sub = currentSubCa();
        if (sub != null) {
            CaStatusVo.SubCaStatus subVo = new CaStatusVo.SubCaStatus();
            subVo.setCn(sub.getSubjectCn());
            subVo.setSerialHex(sub.getSerialHex());
            subVo.setAlgorithm(sub.getAlgorithm());
            subVo.setValidFrom(sub.getValidFrom());
            subVo.setValidTo(sub.getValidTo());
            subVo.setStatus(sub.getStatus());
            subVo.setRenewBeforeDays(props.getSubCa().getRenewBeforeDays());
            vo.setSubCa(subVo);
        }
        return vo;
    }

    /** 根证书 PEM（业务系统信任链配置用） */
    public String exportRootPem() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return root.getCertPem();
    }

    /** 当前 SUB_CA 证书 PEM（中间CA链配置用） */
    public String exportSubCaPem() {
        CaCert sub = currentSubCa();
        if (sub == null) {
            throw new IllegalStateException("SUB_CA未签发");
        }
        return sub.getCertPem();
    }

    /** 签发证书私钥加密落盘 */
    public void storeIssuedKey(String serialHex, String keyPem) {
        vault().storeKey("issued/" + serialHex, "key", keyPem);
    }

    /** 读取签发证书私钥（无则返回 null；已封存/主密钥错误时抛异常） */
    public String loadIssuedKey(String serialHex) {
        return vault().loadKey("issued/" + serialHex, "key");
    }

    public boolean issuedKeyExists(String serialHex) {
        return vault().keyExists("issued/" + serialHex, "key");
    }

    /** 证书过期后自动标记 EXPIRED（仅状态展示，不参与吊销） */
    private void markExpiredIfNeeded(CaCert cert) {
        if ("VALID".equals(cert.getStatus()) && cert.getValidTo() != null
                && cert.getValidTo().isBefore(LocalDateTime.now())) {
            cert.setStatus("EXPIRED");
            certMapper.updateById(cert);
        }
    }
}
