package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.dto.CaStatusVo;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.KeyVault;
import com.csssl.util.PemUtil;
import com.csssl.util.ShamirUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CA 生命周期管理（对齐国内持证CA通行架构：离线根CA → 在线服务签发二级CA）：
 * <ul>
 *   <li>根CA（ROOT）：仅用于初始化与签发 SUB_CA；初始化完成后私钥支持两种离线化形态：
 *       「封存」（key.pem.enc 移存 .sealed）或「Shamir(3,5) 门限分持」（根私钥拆 5 份，任 3 份恢复，
 *       原始单点加密文件删除，对齐 §8.2.4.3 门限控制与密评"根密钥不得单点持有"）。</li>
 *   <li>SUB_CA（服务签发二级CA）：由根签发，承载在线签发/CRL/OCSP 签名；到期由每日预检
 *       {@link #dailyPrecheck()} 在根未封存时轮换，日常签发路径只取现有 SUB_CA、绝不隐式触发需根私钥的操作。</li>
 *   <li>私钥一律经 {@link KeyVault} SM4 加密落盘（key.pem.enc），数据库只存证书与元信息。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaService implements ApplicationRunner {

    public static final String TYPE_ROOT = "ROOT";
    public static final String TYPE_SUB_CA = "SUB_CA";

    /** 根私钥状态 */
    public static final String ROOT_KEY_ONLINE = "ONLINE";
    public static final String ROOT_KEY_SEALED = "SEALED";
    public static final String ROOT_KEY_MISSING = "MISSING";
    /** 根私钥已切换为 Shamir(3,5) 门限分持（份额为唯一持久形态） */
    public static final String ROOT_KEY_SHARES = "SHARES";

    /** Shamir 门限参数：总份额 5、恢复阈值 3 */
    public static final int SHARE_TOTAL = 5;
    public static final int SHARE_THRESHOLD = 3;

    /**
     * 份额解封后的临时根私钥（仅内存，用后 seal 清除）。
     * 所有读写（loadRootKey / seal / unseal / 份额迁移 / ensureSubCa 离线判定）统一持本对象 this 锁，
     * 杜绝"读到刚被封存的私钥"竞态。
     */
    private PrivateKey rootKeyCache;

    private final CssslProperties props;
    private final CaCertMapper certMapper;
    private final ObjectMapper objectMapper;
    /** 单例保险库 bean（KeyVaultConfig 提供），与 TsaService 共用同一 store-dir 实例 */
    private final KeyVault vault;

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
            // 门限化以「份额目录是否已有完整 5 份份额文件」为唯一判据（ensureRootShares 幂等）：
            // 新根生成或中断后重启都会安全重试，不依赖任何内存标志；已封存/已门限态自动跳过
            if (ROOT_KEY_ONLINE.equals(rootKeyState())) {
                ensureRootShares();
            }
            log.info("[CA] 启动完成：root={}({}), subCa 签发就绪，rootKeyState={}",
                    root.getSubjectCn(), root.getAlgorithm(), rootKeyState());
        } catch (Exception e) {
            log.error("[CA] 启动初始化失败，请检查 store-dir 权限、主密钥与数据库连接: {}", e.getMessage());
        }
    }

    // ==================== 根CA ====================

    /**
     * 确保根CA存在（幂等）：优先文件态 → 重建记录 → 全新生成。
     * 根私钥存在性判定兼容两种形态：SM4 加密文件（key.pem.enc）或 Shamir(3,5) 门限份额。
     */
    public synchronized CaCert ensureRoot() {
        CaCert root = findRoot();

        if (root != null && (vault.keyExists("root", "key") || sharesExist())) {
            markExpiredIfNeeded(root);
            return root;
        }
        if (root != null && !vault.keyExists("root", "key") && !sharesExist()) {
            throw new IllegalStateException("根CA私钥缺失（" + rootDir() + "），无法签发二级CA，请恢复密钥或重建根CA");
        }
        if (root == null && Files.exists(rootCertFile()) && vault.keyExists("root", "key")) {
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
        vault.storeKey("root", "key", PemUtil.keyToPem(keyPair.getPrivate()));

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
        // 门限化由调用方（run/initRoot）按"份额文件齐全"判据触发 ensureRootShares，无需内存标志
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

    /**
     * 加载根CA私钥：优先门限解封缓存；否则 SM4 加密文件（旧模式）；份额分持态必须显式解封。
     * 与 seal/unseal/份额迁移同为 this 锁方法：读缓存与封存写入互斥，杜绝"读到刚被封存的私钥"。
     */
    public synchronized PrivateKey loadRootKey() {
        if (rootKeyCache != null) {
            return rootKeyCache;
        }
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        String keyPem = vault.loadKey("root", "key");
        if (keyPem == null) {
            if (sharesExist()) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请由密钥管理员提交任意 3 份份额解封后再轮换 SUB_CA");
            }
            throw new IllegalStateException("根CA私钥缺失，请恢复密钥或重建根CA");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    /** 根私钥状态：SHARES（门限分持）/ ONLINE / SEALED / MISSING */
    public String rootKeyState() {
        if (sharesExist()) {
            return ROOT_KEY_SHARES;
        }
        if (vault.isSealed("root", "key")) {
            return ROOT_KEY_SEALED;
        }
        return vault.keyExists("root", "key") ? ROOT_KEY_ONLINE : ROOT_KEY_MISSING;
    }

    /** 封存根私钥：门限分持态清除内存缓存；旧模式将加密文件移存 .sealed（幂等） */
    public synchronized void sealRootKey() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法封存");
        }
        ensureSubCa();
        rootKeyCache = null;
        if (vault.keyExists("root", "key")) {
            vault.sealKey("root", "key");
        }
        log.info("[根CA] 根私钥已封存（serial={}，门限分持态={}），日常签发由 SUB_CA 承担",
                root.getSerialHex(), sharesExist());
    }

    /** 解封根私钥（旧模式移名恢复在线态；门限分持态须走份额解封接口） */
    public synchronized void unsealRootKey() {
        if (findRoot() == null) {
            throw new IllegalStateException("根CA未初始化，无法解封");
        }
        if (sharesExist()) {
            throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请用份额解封接口提交任意 3 份");
        }
        vault.unsealKey("root", "key");
        if (vault.isSealed("root", "key")) {
            throw new IllegalStateException("根私钥文件缺失（已封存），无法解封");
        }
        log.info("[根CA] 根私钥已解封，恢复在线态");
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /** 份额目录：store-dir/root/shares/shares-{1..5}.json */
    private Path sharesDir() {
        return rootDir().resolve("shares");
    }

    private Path shareFile(int index) {
        return sharesDir().resolve("shares-" + index + ".json");
    }

    /** 读取单份门限份额 JSON 文本（管理端导出用；不存在抛 IllegalStateException） */
    public String readShareJson(int index) {
        Path file = shareFile(index);
        if (!Files.exists(file)) {
            throw new IllegalStateException("份额文件不存在（编号 " + index + "）：请先执行门限化迁移");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("份额文件读取失败: " + e.getMessage(), e);
        }
    }

    /** 5 份份额是否全部生成（任一缺失视为未完成，避免以不完整份额误判） */
    public boolean sharesExist() {
        if (!Files.isDirectory(sharesDir())) {
            return false;
        }
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            if (!Files.exists(shareFile(i))) {
                return false;
            }
        }
        return true;
    }

    /** 份额状态（管理端展示：5 份各自存在性 + 阈值） */
    public Map<String, Object> shareState() {
        List<Map<String, Object>> shares = new ArrayList<>();
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            shares.add(Map.of("index", i, "exists", Files.exists(shareFile(i))));
        }
        return Map.of("generated", sharesExist(), "total", SHARE_TOTAL,
                "threshold", SHARE_THRESHOLD, "shares", shares);
    }

    /**
     * 根私钥门限化（幂等）：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 仅当 5 份份额全部成功落盘后才删除原始文件；任何一份失败则保留原始文件并报错（不静默降级）。
     * 触发：run()/initRoot() 以「份额文件是否齐全」为唯一判据调用（中断/重启后可安全重试）；旧环境也可由管理员显式调用迁移接口。
     */
    public synchronized void ensureRootShares() {
        if (sharesExist()) {
            return;
        }
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法生成门限份额");
        }
        String keyPem = vault.loadKey("root", "key");
        if (keyPem == null) {
            throw new IllegalStateException("根私钥不可用（无 SM4 加密副本可拆分），无法生成门限份额");
        }
        try {
            Files.createDirectories(sharesDir());
            PrivateKey key = PemUtil.parsePrivateKey(keyPem);
            ShamirUtil.Share[] shares = ShamirUtil.split(key.getEncoded(), SHARE_TOTAL, SHARE_THRESHOLD);
            for (ShamirUtil.Share s : shares) {
                Files.writeString(shareFile(s.index()),
                        objectMapper.writeValueAsString(s), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("门限份额生成失败（原始根私钥未删除）: " + e.getMessage(), e);
        }
        // 5 份份额全部落盘成功后才删除单点加密文件
        if (sharesExist()) {
            vault.deleteKey("root", "key");
            rootKeyCache = null;
            log.warn("[根CA] 根私钥已切换为 Shamir(3,5) 门限分持（serial={}）：5 份份额落盘，原始加密文件已删除，恢复需任意 3 份",
                    root.getSerialHex());
        } else {
            throw new IllegalStateException("份额写入不完整，已保留原始根私钥，请检查份额目录后重试");
        }
    }

    /** 旧环境迁移：把已存在的 SM4 加密根私钥转为门限份额（显式调用，避免误删生产密钥） */
    public synchronized Map<String, Object> migrateRootShares() {
        if (sharesExist()) {
            return shareState();
        }
        ensureRootShares();
        return shareState();
    }

    /**
     * 份额解封：提交任意 3 份门限份额，重组根私钥并置入内存缓存（仅本次进程有效）。
     * 重组结果用根证书公钥做签名挑战校验，防止份额被篡改/错配。
     */
    public synchronized void unsealRootKeyWithShares(List<String> shareJsons) {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法解封");
        }
        if (shareJsons == null || shareJsons.size() < SHARE_THRESHOLD) {
            throw new IllegalArgumentException("至少需要提交 " + SHARE_THRESHOLD + " 份门限份额");
        }
        try {
            List<ShamirUtil.Share> shares = new ArrayList<>();
            Set<Integer> indexes = new java.util.HashSet<>();
            for (String json : shareJsons) {
                ShamirUtil.Share s = objectMapper.readValue(json, ShamirUtil.Share.class);
                if (s.index() < 1 || s.index() > SHARE_TOTAL || !indexes.add(s.index())) {
                    throw new IllegalArgumentException("份额编号无效或重复（有效范围 1~" + SHARE_TOTAL + "，须提交不同编号）");
                }
                shares.add(s);
            }
            byte[] der = ShamirUtil.recover(shares.toArray(new ShamirUtil.Share[0]));
            PrivateKey key = PemUtil.parsePrivateKey(PemUtil.toPem("PRIVATE KEY", der));
            verifyRootKeyMatch(key, root);
            rootKeyCache = key;
            log.warn("[根CA] 已通过 {} 份门限份额解封根私钥（内存缓存），使用后请及时封存", shares.size());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("份额解封失败: " + e.getMessage(), e);
        }
    }

    /** 用根证书公钥校验重组私钥匹配（签名挑战，防份额错配/篡改） */
    private void verifyRootKeyMatch(PrivateKey key, CaCert root) {
        byte[] challenge = "csssl-root-key-shamir-verify".getBytes(StandardCharsets.UTF_8);
        try {
            Signature sig = Signature.getInstance(CertUtil.signatureAlgorithmForKey(key), "BC");
            sig.initSign(key);
            sig.update(challenge);
            byte[] out = sig.sign();
            PublicKey pub = PemUtil.parseCert(root.getCertPem()).getPublicKey();
            Signature ver = Signature.getInstance(CertUtil.signatureAlgorithmForKey(key), "BC");
            ver.initVerify(pub);
            ver.update(challenge);
            if (!ver.verify(out)) {
                throw new IllegalStateException("份额重组密钥与根证书不匹配，请核对份额来源");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("根密钥匹配校验失败: " + e.getMessage(), e);
        }
    }

    /** 管理端主动初始化：已就绪则原样返回；根私钥丢失则吊销旧根并重建（连带吊销旧SUB_CA） */
    public synchronized CaStatusVo initRoot() {
        CaCert root = findRoot();
        if (root != null && (vault.keyExists("root", "key") || sharesExist())) {
            return status();
        }
        if (root != null) {
            revokeAllSubCa("ROOT_KEY_LOST", root.getSubjectCn());
            root.setStatus("REVOKED");
            root.setRevokeReason("ROOT_KEY_LOST");
            root.setRevokedAt(LocalDateTime.now());
            certMapper.updateById(root);
            // 清理旧根密钥残留（含半套旧份额）：防遗留份额文件被"份额齐全"判据误命中、跳过新根门限化
            clearRootKeyMaterial();
            log.warn("[根CA] 旧根私钥缺失，已吊销（serial={}）并重建新根", root.getSerialHex());
        }
        CaCert created = generateRoot();
        ensureSubCa();
        // 新根自动切换为 Shamir(3,5) 门限分持（删单点加密文件）
        ensureRootShares();
        CaStatusVo vo = status();
        vo.setMessage("根CA已重建：" + created.getSubjectCn() + "（序列号 " + created.getSerialHex() + "），已签发新SUB_CA，根私钥已切换为 Shamir(3,5) 门限分持（请尽快导存 5 份份额）");
        return vo;
    }

    /** 吊销全部有效 SUB_CA（根重建/根丢失时调用，旧链整体失效），并清理其磁盘私钥（仅 CA 类密钥） */
    private void revokeAllSubCa(String reason, String rootCn) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA));
        for (CaCert sub : subs) {
            if (!"REVOKED".equals(sub.getStatus())) {
                sub.setStatus("REVOKED");
                sub.setRevokeReason(reason);
                sub.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(sub);
                purgeCaKeyFiles(subCaDir(), sub.getSerialHex());
            }
        }
    }

    /**
     * 清理被吊销 CA 的磁盘私钥文件（*.key.pem.enc / *.key.pem.enc.sealed / 遗留明文）。
     * 只对 ROOT/SUB_CA 类密钥执行；叶证书私钥随证书吊销不删（院内数据恢复场景需要）。
     */
    private void purgeCaKeyFiles(Path dir, String serialHex) {
        for (String fileName : new String[]{"key.pem.enc", "key.pem.enc.sealed", "key.pem"}) {
            Path f = dir.resolve(fileName);
            try {
                if (Files.deleteIfExists(f)) {
                    log.info("[CA密钥清理] 已删除被吊销CA私钥文件: {}（serial={}）", f, serialHex);
                }
            } catch (IOException e) {
                log.warn("[CA密钥清理] 删除失败 {}（serial={}）: {}", f, serialHex, e.getMessage());
            }
        }
    }

    /** 根重建前清理旧根密钥残留：全形态加密文件 + 旧门限份额文件 */
    private void clearRootKeyMaterial() {
        purgeCaKeyFiles(rootDir(), "ROOT");
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            Path stale = shareFile(i);
            try {
                if (Files.deleteIfExists(stale)) {
                    log.info("[根CA] 重建前已删除旧根遗留份额文件: {}", stale);
                }
            } catch (IOException e) {
                log.warn("[根CA] 旧份额文件删除失败（不阻断重建）: {}", e.getMessage());
            }
        }
    }

    // ==================== SUB_CA（服务签发二级CA） ====================

    /**
     * 确保 SUB_CA 存在（幂等，新建/轮换专用）：仅限启动、封存窗口（sealRootKey/initRoot）
     * 与每日预检 {@link #dailyPrecheck()} 调用；日常签发路径不再经过这里。
     */
    public synchronized CaCert ensureSubCa() {
        CaCert sub = findValidSubCa();
        if (sub != null && vault.keyExists("subca", "key")) {
            return sub;
        }
        // 根私钥离线（封存/门限分持未解封）时不允许新建/轮换 SUB_CA
        String rkState = rootKeyState();
        if (sub == null && (ROOT_KEY_SEALED.equals(rkState)
                || (ROOT_KEY_SHARES.equals(rkState) && rootKeyCache == null))) {
            throw new IllegalStateException("根CA私钥离线（封存/门限分持）且无有效 SUB_CA，请先解封根密钥后轮换签发");
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
        vault.storeKey("subca", "key", PemUtil.keyToPem(subKey.getPrivate()));

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
        // 临近到期（< renewBeforeDays）视为不可继续签发，由 dailyPrecheck 在根可用时轮换
        LocalDateTime renewAt = sub.getValidTo() == null ? null
                : sub.getValidTo().minusDays(props.getSubCa().getRenewBeforeDays());
        if (renewAt != null && renewAt.isBefore(LocalDateTime.now())) {
            return null;
        }
        return sub;
    }

    /**
     * 每日到期预检（定时任务，与签发路径解耦）：SUB_CA 缺失/进入 renewBeforeDays 续期窗口
     * 且根私钥可用（在线或份额已解封）时轮换；根封存期间只告警，绝不隐式触发需根私钥的操作。
     */
    @Scheduled(cron = "${csssl.subca-precheck-cron:0 30 3 * * ?}")
    public synchronized void dailyPrecheck() {
        try {
            if (findValidSubCa() != null && vault.keyExists("subca", "key")) {
                return;
            }
            String rkState = rootKeyState();
            boolean rootUsable = ROOT_KEY_ONLINE.equals(rkState)
                    || (ROOT_KEY_SHARES.equals(rkState) && rootKeyCache != null);
            if (!rootUsable) {
                log.warn("[SUB_CA] 预检发现 SUB_CA 缺失/临近到期，但根私钥离线（{}），请在根解封窗口轮换", rkState);
                return;
            }
            ensureSubCa();
            log.info("[SUB_CA] 到期预检完成轮换");
        } catch (Exception e) {
            log.warn("[SUB_CA] 到期预检失败: {}", e.getMessage());
        }
    }

    /** 当前签发CA（SUB_CA）证书：只取现有有效 SUB_CA，不触发轮换（轮换见 {@link #dailyPrecheck()}） */
    public X509Certificate signingCert() {
        return PemUtil.parseCert(requireValidSubCa().getCertPem());
    }

    public PrivateKey signingKey() {
        requireValidSubCa();
        String keyPem = vault.loadKey("subca", "key");
        if (keyPem == null) {
            throw new IllegalStateException("SUB_CA私钥缺失，请在根解封窗口轮换");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    private CaCert requireValidSubCa() {
        CaCert sub = findValidSubCa();
        if (sub == null) {
            throw new IllegalStateException("SUB_CA已过期，请在根解封窗口轮换");
        }
        return sub;
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
            if (Files.exists(rootCertFile()) || vault.keyExists("root", "key")) {
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
        vo.setShareState(shareState());
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
        vault.storeKey("issued/" + serialHex, "key", keyPem);
    }

    /** 读取签发证书私钥（无则返回 null；已封存/主密钥错误时抛异常） */
    public String loadIssuedKey(String serialHex) {
        return vault.loadKey("issued/" + serialHex, "key");
    }

    public boolean issuedKeyExists(String serialHex) {
        return vault.keyExists("issued/" + serialHex, "key");
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
