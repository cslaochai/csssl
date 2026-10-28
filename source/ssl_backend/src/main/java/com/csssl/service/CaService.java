package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.dto.CaStatusVo;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaOrg;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CaOrgMapper;
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
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>路线A多机构（按机构独立根CA）：本类维护 {@code orgKey → OrgCaState} 根池，
 * 每机构独立的锁、门限解封内存缓存、目录路径与保险库（{@link KeyVaultConfig.OrgVaultResolver}）。
 * 目录规则：default（存量单根，兼容铁律）沿用 store-dir/{root,subca,issued} 与 root/shares、master.key；
 * 新机构为 store-dir/orgs/{orgKey}/{root,subca,issued,shares} 与 orgs/{orgKey}/master.key。
 * 所有原无参公开方法委托 default 机构，行为与单根时代完全一致；新代码一律使用带 orgKey 的版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaService implements ApplicationRunner {

    public static final String TYPE_ROOT = "ROOT";
    public static final String TYPE_SUB_CA = "SUB_CA";

    /** 院内默认域机构（存量单根兼容位）：所有无 orgKey 参数的公开方法均作用于它 */
    public static final String DEFAULT_ORG_KEY = CaOrg.DEFAULT_ORG_KEY;

    /** 根私钥状态 */
    public static final String ROOT_KEY_ONLINE = "ONLINE";
    public static final String ROOT_KEY_SEALED = "SEALED";
    public static final String ROOT_KEY_MISSING = "MISSING";
    /** 根私钥已切换为 Shamir(3,5) 门限分持（份额为唯一持久形态） */
    public static final String ROOT_KEY_SHARES = "SHARES";

    /** Shamir 门限参数：总份额 5、恢复阈值 3 */
    public static final int SHARE_TOTAL = 5;
    public static final int SHARE_THRESHOLD = 3;

    private final CssslProperties props;
    private final CaCertMapper certMapper;
    private final ObjectMapper objectMapper;
    /** 单例保险库 bean（KeyVaultConfig 提供），与 TsaService 共用同一 store-dir 实例；即 default 机构保险库 */
    private final KeyVault vault;
    /** 机构保险库解析器：default → 上述单例；其他机构 → store-dir/orgs/{orgKey} 独立保险库 */
    private final KeyVaultConfig.OrgVaultResolver orgVaults;
    private final CaOrgMapper orgMapper;

    /**
     * 机构根CA状态池（computeIfAbsent 懒加载）。每机构一把 per-state 锁：
     * 沿用 B 路线「单一锁对象守护内存密钥读写」的语义，细化到机构粒度——
     * 杜绝"读到刚被封存的私钥"竞态，同时不同机构互不阻塞。
     */
    private final Map<String, OrgCaState> orgStates = new ConcurrentHashMap<>();

    private static final class OrgCaState {
        /** 机构锁对象：该机构 rootKeyCache 及根/子CA密钥读写的唯一互斥域 */
        final Object lock = new Object();
        /**
         * 份额解封后的临时根私钥（仅内存，用后 seal 清除）。
         * 所有读写（loadRootKey / seal / unseal / 份额迁移 / ensureSubCa 离线判定）统一持本机构锁。
         */
        PrivateKey rootKeyCache;
    }

    // ==================== 机构态/路径/保险库解析 ====================

    private OrgCaState stateOf(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        return orgStates.computeIfAbsent(orgKey, k -> new OrgCaState());
    }

    /** 机构保险库：default 复用共享单例，其他机构按 orgs/{orgKey} 根独立（含独立主密钥） */
    private KeyVault vaultFor(String orgKey) {
        return orgVaults.forOrg(orgKey);
    }

    /** 机构落盘根目录：default → store-dir；其他 → store-dir/orgs/{orgKey} */
    private Path orgBaseDir(String orgKey) {
        return orgVaults.orgStoreDir(orgKey);
    }

    private Path rootDir(String orgKey) {
        return orgBaseDir(orgKey).resolve("root");
    }

    private Path rootCertFile(String orgKey) {
        return rootDir(orgKey).resolve("cert.pem");
    }

    private Path subCaDir(String orgKey) {
        return orgBaseDir(orgKey).resolve("subca");
    }

    private Path subCaCertFile(String orgKey) {
        return subCaDir(orgKey).resolve("cert.pem");
    }

    /** 签发证书落盘目录（issued/{serial}，兼容入口=default 机构） */
    public Path issuedDir(String serialHex) {
        return issuedDir(DEFAULT_ORG_KEY, serialHex);
    }

    /** 签发证书落盘目录：default → store-dir/issued/{serial}；其他 → store-dir/orgs/{orgKey}/issued/{serial} */
    public Path issuedDir(String orgKey, String serialHex) {
        return orgBaseDir(orgKey).resolve("issued").resolve(serialHex);
    }

    /** 门限份额目录：default 沿用 root/shares（存量路径不动）；新机构按约定平铺在机构目录 shares/ */
    private Path sharesDir(String orgKey) {
        return DEFAULT_ORG_KEY.equals(orgKey)
                ? rootDir(orgKey).resolve("shares")
                : orgBaseDir(orgKey).resolve("shares");
    }

    private Path shareFile(String orgKey, int index) {
        return sharesDir(orgKey).resolve("shares-" + index + ".json");
    }

    /** 机构记录（不存在即拒绝操作——路径/密钥材料都锚定注册表，防未登记机构污染磁盘） */
    private CaOrg requireOrg(String orgKey) {
        CaOrg org = orgMapper.selectOne(new LambdaQueryWrapper<CaOrg>().eq(CaOrg::getOrgKey, orgKey));
        if (org == null) {
            throw new IllegalStateException("机构不存在: " + orgKey);
        }
        return org;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动仅自动确保 default 机构（存量行为）：新机构由 OrgService 开机构流程初始化
        try {
            CaCert root = ensureRoot(DEFAULT_ORG_KEY);
            ensureSubCa(DEFAULT_ORG_KEY);
            // 门限化以「份额目录是否已有完整 5 份份额文件」为唯一判据（ensureRootShares 幂等）：
            // 新根生成或中断后重启都会安全重试，不依赖任何内存标志；已封存/已门限态自动跳过
            if (ROOT_KEY_ONLINE.equals(rootKeyState(DEFAULT_ORG_KEY))) {
                ensureRootShares(DEFAULT_ORG_KEY);
            }
            log.info("[CA] 启动完成（default 域）：root={}({}), subCa 签发就绪，rootKeyState={}",
                    root.getSubjectCn(), root.getAlgorithm(), rootKeyState(DEFAULT_ORG_KEY));
        } catch (Exception e) {
            log.error("[CA] 启动初始化失败，请检查 store-dir 权限、主密钥与数据库连接: {}", e.getMessage());
        }
    }

    // ==================== 根CA ====================

    /** 确保根CA存在（幂等，兼容入口=default 机构） */
    public CaCert ensureRoot() {
        return ensureRoot(DEFAULT_ORG_KEY);
    }

    /**
     * 确保指定机构根CA存在（幂等）：优先文件态 → 重建记录 → 全新生成。
     * 根私钥存在性判定兼容两种形态：SM4 加密文件（key.pem.enc）或 Shamir(3,5) 门限份额。
     */
    public CaCert ensureRoot(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(orgKey);

            if (root != null && (ov.keyExists("root", "key") || doSharesExist(orgKey))) {
                markExpiredIfNeeded(root);
                return root;
            }
            if (root != null && !ov.keyExists("root", "key") && !doSharesExist(orgKey)) {
                throw new IllegalStateException("根CA私钥缺失（" + rootDir(orgKey) + "），无法签发二级CA，请恢复密钥或重建根CA");
            }
            if (root == null && Files.exists(rootCertFile(orgKey)) && ov.keyExists("root", "key")) {
                CaCert rebuilt = doRebuildFromFiles(orgKey);
                log.warn("[根CA][{}] 数据库记录缺失，已从落盘文件重建（serial={}）", orgKey, rebuilt.getSerialHex());
                return rebuilt;
            }
            return doGenerateRoot(orgKey, ov);
        }
    }

    /** 全新生成根CA（管理端 /admin/ca/init 与启动初始化、开机构流程共用） */
    private CaCert doGenerateRoot(String orgKey, KeyVault ov) {
        String algorithm;
        X509Certificate cert;
        KeyPair keyPair;
        if (DEFAULT_ORG_KEY.equals(orgKey)) {
            // default 域：CN/算法/有效期全部沿用全局配置与旧4参自签（DN 含 O=CSSSL），存量行为零变化
            CssslProperties.RootCa rootProps = props.getRootCa();
            algorithm = rootProps.getAlgorithm() == null || rootProps.getAlgorithm().isBlank()
                    ? CertUtil.ALG_RSA2048 : rootProps.getAlgorithm().trim();
            CertUtil.checkAlgorithm(algorithm);
            keyPair = CertUtil.generateKeyPair(algorithm);
            cert = CertUtil.selfSignRoot(keyPair, rootProps.getCn(), algorithm, rootProps.getValidityDays());
        } else {
            CaOrg org = requireOrg(orgKey);
            algorithm = org.getAlgorithm() == null || org.getAlgorithm().isBlank()
                    ? props.getRootCa().getAlgorithm() : org.getAlgorithm().trim();
            CertUtil.checkAlgorithm(algorithm);
            keyPair = CertUtil.generateKeyPair(algorithm);
            cert = CertUtil.selfSignRoot(keyPair, "CSSSL ROOT CA - " + org.getOrgName(),
                    org.getSubjectO(), algorithm, props.getRootCa().getValidityDays());
        }
        String serial = cert.getSerialNumber().toString(16);

        try {
            Files.createDirectories(rootDir(orgKey));
            Files.writeString(rootCertFile(orgKey), PemUtil.certToPem(cert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("根CA落盘失败: " + e.getMessage(), e);
        }
        ov.storeKey("root", "key", PemUtil.keyToPem(keyPair.getPrivate()));

        CaCert row = new CaCert();
        row.setOrgKey(orgKey);
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
        row.setDescription(DEFAULT_ORG_KEY.equals(orgKey)
                ? "系统根CA（首次启动自动初始化）" : "机构根CA（开机构时初始化）");
        row.setCreatedBy("SYSTEM");
        row.setCreatedAt(LocalDateTime.now());
        certMapper.insert(row);
        // 门限化由调用方（run/initRoot）按"份额文件齐全"判据触发 ensureRootShares，无需内存标志
        log.info("[根CA][{}] 初始化完成：cn={}, algorithm={}, serial={}", orgKey, row.getSubjectCn(), algorithm, serial);
        return row;
    }

    /** 数据库记录缺失但落盘文件存在时，从文件重建记录 */
    private CaCert doRebuildFromFiles(String orgKey) {
        try {
            String certPem = Files.readString(rootCertFile(orgKey), StandardCharsets.UTF_8);
            X509Certificate cert = PemUtil.parseCert(certPem);
            // 算法探测沿用存量口径（按配置判断 SM2/RSA）；机构域以注册表 algorithm 为准
            String hint = DEFAULT_ORG_KEY.equals(orgKey)
                    ? props.getRootCa().getAlgorithm() : requireOrg(orgKey).getAlgorithm();
            CaCert row = new CaCert();
            row.setOrgKey(orgKey);
            row.setSerialHex(cert.getSerialNumber().toString(16));
            row.setSubjectCn(CertUtil.subjectCn(cert));
            row.setCertType(TYPE_ROOT);
            row.setAlgorithm(CertUtil.isSm2(hint) ? CertUtil.ALG_SM2 : CertUtil.ALG_RSA2048);
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

    /** 查询根CA记录（兼容入口=default 机构） */
    public CaCert findRoot() {
        return findRoot(DEFAULT_ORG_KEY);
    }

    /** 查询指定机构根CA记录（serial 全局唯一，机构维度过滤取最新） */
    public CaCert findRoot(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        return doFindRoot(orgKey);
    }

    private CaCert doFindRoot(String orgKey) {
        List<CaCert> roots = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_ROOT)
                .eq(CaCert::getOrgKey, orgKey)
                .orderByDesc(CaCert::getCreatedAt));
        return roots.isEmpty() ? null : roots.get(0);
    }

    /** 加载根证书（兼容入口=default 机构） */
    public X509Certificate loadRootCert() {
        return loadRootCert(DEFAULT_ORG_KEY);
    }

    /** 加载指定机构根证书 */
    public X509Certificate loadRootCert(String orgKey) {
        CaCert root = findRoot(orgKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /** 加载根CA私钥（兼容入口=default 机构） */
    public PrivateKey loadRootKey() {
        return loadRootKey(DEFAULT_ORG_KEY);
    }

    /** 加载机构根证书（内部版，假定调用方已持有机构锁或仅需只读一致性） */
    private X509Certificate doLoadRootCert(String orgKey) {
        CaCert root = doFindRoot(orgKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /**
     * 加载机构根CA私钥：优先门限解封缓存；否则 SM4 加密文件（旧模式）；份额分持态必须显式解封。
     * 与 seal/unseal/份额迁移同为机构锁方法：读缓存与封存写入互斥，杜绝"读到刚被封存的私钥"。
     */
    public PrivateKey loadRootKey(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            return doLoadRootKey(orgKey, ov, st);
        }
    }

    private PrivateKey doLoadRootKey(String orgKey, KeyVault ov, OrgCaState st) {
        if (st.rootKeyCache != null) {
            return st.rootKeyCache;
        }
        CaCert root = doFindRoot(orgKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        String keyPem = ov.loadKey("root", "key");
        if (keyPem == null) {
            if (doSharesExist(orgKey)) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请由密钥管理员提交任意 3 份份额解封后再轮换 SUB_CA");
            }
            throw new IllegalStateException("根CA私钥缺失，请恢复密钥或重建根CA");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    /** 根私钥状态（兼容入口=default 机构） */
    public String rootKeyState() {
        return rootKeyState(DEFAULT_ORG_KEY);
    }

    /** 机构根私钥状态：SHARES（门限分持）/ ONLINE / SEALED / MISSING */
    public String rootKeyState(String orgKey) {
        return doRootKeyState(orgKey, vaultFor(orgKey));
    }

    private String doRootKeyState(String orgKey, KeyVault ov) {
        if (doSharesExist(orgKey)) {
            return ROOT_KEY_SHARES;
        }
        if (ov.isSealed("root", "key")) {
            return ROOT_KEY_SEALED;
        }
        return ov.keyExists("root", "key") ? ROOT_KEY_ONLINE : ROOT_KEY_MISSING;
    }

    /** 封存根私钥（兼容入口=default 机构） */
    public void sealRootKey() {
        sealRootKey(DEFAULT_ORG_KEY);
    }

    /** 封存机构根私钥：门限分持态清除内存缓存；旧模式将加密文件移存 .sealed（幂等） */
    public void sealRootKey(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(orgKey);
            if (root == null) {
                throw new IllegalStateException("根CA未初始化，无法封存");
            }
            doEnsureSubCa(orgKey, ov, st);
            st.rootKeyCache = null;
            if (ov.keyExists("root", "key")) {
                ov.sealKey("root", "key");
            }
            log.info("[根CA][{}] 根私钥已封存（serial={}，门限分持态={}），日常签发由 SUB_CA 承担",
                    orgKey, root.getSerialHex(), doSharesExist(orgKey));
        }
    }

    /** 解封根私钥（兼容入口=default 机构） */
    public void unsealRootKey() {
        unsealRootKey(DEFAULT_ORG_KEY);
    }

    /** 解封机构根私钥（旧模式移名恢复在线态；门限分持态须走份额解封接口） */
    public void unsealRootKey(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            if (doFindRoot(orgKey) == null) {
                throw new IllegalStateException("根CA未初始化，无法解封");
            }
            if (doSharesExist(orgKey)) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请用份额解封接口提交任意 3 份");
            }
            ov.unsealKey("root", "key");
            if (ov.isSealed("root", "key")) {
                throw new IllegalStateException("根私钥文件缺失（已封存），无法解封");
            }
            log.info("[根CA][{}] 根私钥已解封，恢复在线态", orgKey);
        }
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /** 读取单份门限份额 JSON 文本（兼容入口=default 机构） */
    public String readShareJson(int index) {
        return readShareJson(DEFAULT_ORG_KEY, index);
    }

    /** 读取机构单份门限份额 JSON 文本（管理端导出用；不存在抛 IllegalStateException） */
    public String readShareJson(String orgKey, int index) {
        Path file = shareFile(orgKey, index);
        if (!Files.exists(file)) {
            throw new IllegalStateException("份额文件不存在（编号 " + index + "）：请先执行门限化迁移");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("份额文件读取失败: " + e.getMessage(), e);
        }
    }

    /** 5 份份额是否全部生成（兼容入口=default 机构） */
    public boolean sharesExist() {
        return sharesExist(DEFAULT_ORG_KEY);
    }

    /** 机构 5 份份额是否全部生成（任一缺失视为未完成，避免以不完整份额误判） */
    public boolean sharesExist(String orgKey) {
        return doSharesExist(orgKey);
    }

    private boolean doSharesExist(String orgKey) {
        if (!Files.isDirectory(sharesDir(orgKey))) {
            return false;
        }
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            if (!Files.exists(shareFile(orgKey, i))) {
                return false;
            }
        }
        return true;
    }

    /** 份额状态（兼容入口=default 机构） */
    public Map<String, Object> shareState() {
        return shareState(DEFAULT_ORG_KEY);
    }

    /** 机构份额状态（管理端展示：5 份各自存在性 + 阈值） */
    public Map<String, Object> shareState(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        List<Map<String, Object>> shares = new ArrayList<>();
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            shares.add(Map.of("index", i, "exists", Files.exists(shareFile(orgKey, i))));
        }
        return Map.of("generated", doSharesExist(orgKey), "total", SHARE_TOTAL,
                "threshold", SHARE_THRESHOLD, "shares", shares);
    }

    /** 根私钥门限化（兼容入口=default 机构） */
    public void ensureRootShares() {
        ensureRootShares(DEFAULT_ORG_KEY);
    }

    /**
     * 机构根私钥门限化（幂等）：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 仅当 5 份份额全部成功落盘后才删除原始文件；任何一份失败则保留原始文件并报错（不静默降级）。
     * 触发：run()/initRoot() 以「份额文件是否齐全」为唯一判据调用（中断/重启后可安全重试）；旧环境也可由管理员显式调用迁移接口。
     */
    public void ensureRootShares(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            doEnsureRootShares(orgKey, ov, st);
        }
    }

    private void doEnsureRootShares(String orgKey, KeyVault ov, OrgCaState st) {
        if (doSharesExist(orgKey)) {
            return;
        }
        CaCert root = doFindRoot(orgKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法生成门限份额");
        }
        String keyPem = ov.loadKey("root", "key");
        if (keyPem == null) {
            throw new IllegalStateException("根私钥不可用（无 SM4 加密副本可拆分），无法生成门限份额");
        }
        try {
            Files.createDirectories(sharesDir(orgKey));
            PrivateKey key = PemUtil.parsePrivateKey(keyPem);
            ShamirUtil.Share[] shares = ShamirUtil.split(key.getEncoded(), SHARE_TOTAL, SHARE_THRESHOLD);
            for (ShamirUtil.Share s : shares) {
                Files.writeString(shareFile(orgKey, s.index()),
                        objectMapper.writeValueAsString(s), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("门限份额生成失败（原始根私钥未删除）: " + e.getMessage(), e);
        }
        // 5 份份额全部落盘成功后才删除单点加密文件
        if (doSharesExist(orgKey)) {
            ov.deleteKey("root", "key");
            st.rootKeyCache = null;
            log.warn("[根CA][{}] 根私钥已切换为 Shamir(3,5) 门限分持（serial={}）：5 份份额落盘，原始加密文件已删除，恢复需任意 3 份",
                    orgKey, root.getSerialHex());
        } else {
            throw new IllegalStateException("份额写入不完整，已保留原始根私钥，请检查份额目录后重试");
        }
    }

    /** 旧环境迁移（兼容入口=default 机构） */
    public Map<String, Object> migrateRootShares() {
        return migrateRootShares(DEFAULT_ORG_KEY);
    }

    /** 机构迁移：把已存在的 SM4 加密根私钥转为门限份额（显式调用，避免误删生产密钥） */
    public Map<String, Object> migrateRootShares(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            if (doSharesExist(orgKey)) {
                return shareState(orgKey);
            }
            doEnsureRootShares(orgKey, ov, st);
            return shareState(orgKey);
        }
    }

    /** 份额解封（兼容入口=default 机构） */
    public void unsealRootKeyWithShares(List<String> shareJsons) {
        unsealRootKeyWithShares(DEFAULT_ORG_KEY, shareJsons);
    }

    /**
     * 机构份额解封：提交任意 3 份门限份额，重组根私钥并置入该机构内存缓存（仅本次进程有效）。
     * 重组结果用该机构根证书公钥做签名挑战校验，防止份额被篡改/错配。
     */
    public void unsealRootKeyWithShares(String orgKey, List<String> shareJsons) {
        OrgCaState st = stateOf(orgKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(orgKey);
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
                st.rootKeyCache = key;
                log.warn("[根CA][{}] 已通过 {} 份门限份额解封根私钥（内存缓存），使用后请及时封存", orgKey, shares.size());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("份额解封失败: " + e.getMessage(), e);
            }
        }
    }

    /** 用根证书公钥校验重组私钥匹配（签名挑战，防份额错配/篡改；份额天然按机构目录存放，跨机构份额无法互用） */
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

    /** 管理端主动初始化（兼容入口=default 机构） */
    public CaStatusVo initRoot() {
        return initRoot(DEFAULT_ORG_KEY);
    }

    /**
     * 机构根CA初始化：已就绪则原样返回；根私钥丢失则吊销旧根并重建（连带吊销旧SUB_CA）。
     * default 域保持存量 CN/算法配置；新机构按注册表 algorithm/subject_o 生成独立根。
     */
    public CaStatusVo initRoot(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(orgKey);
            if (root != null && (ov.keyExists("root", "key") || doSharesExist(orgKey))) {
                // 幂等修复路径：首建中途失败会留下"有根无SUB_CA"的裸域，此处补建（已有有效SUB_CA即直接复用）
                doEnsureSubCa(orgKey, ov, st);
                return doStatus(orgKey, ov);
            }
            if (root != null) {
                doRevokeAllSubCa(orgKey, ov, "ROOT_KEY_LOST", root.getSubjectCn());
                root.setStatus("REVOKED");
                root.setRevokeReason("ROOT_KEY_LOST");
                root.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(root);
                // 清理旧根密钥残留（含半套旧份额）：防遗留份额文件被"份额齐全"判据误命中、跳过新根门限化
                doClearRootKeyMaterial(orgKey, ov);
                log.warn("[根CA][{}] 旧根私钥缺失，已吊销（serial={}）并重建新根", orgKey, root.getSerialHex());
            }
            CaCert created = doGenerateRoot(orgKey, ov);
            doEnsureSubCa(orgKey, ov, st);
            // 新根自动切换为 Shamir(3,5) 门限分持（删单点加密文件）
            doEnsureRootShares(orgKey, ov, st);
            CaStatusVo vo = doStatus(orgKey, ov);
            vo.setMessage("根CA已重建：" + created.getSubjectCn() + "（序列号 " + created.getSerialHex() + "），已签发新SUB_CA，根私钥已切换为 Shamir(3,5) 门限分持（请尽快导存 5 份份额）");
            return vo;
        }
    }

    /** 吊销该机构全部有效 SUB_CA（根重建/根丢失时调用，旧链整体失效），并清理其磁盘私钥（仅 CA 类密钥） */
    private void doRevokeAllSubCa(String orgKey, KeyVault ov, String reason, String rootCn) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getOrgKey, orgKey));
        for (CaCert sub : subs) {
            if (!"REVOKED".equals(sub.getStatus())) {
                sub.setStatus("REVOKED");
                sub.setRevokeReason(reason);
                sub.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(sub);
                purgeCaKeyFiles(subCaDir(orgKey), ov, sub.getSerialHex());
            }
        }
    }

    /**
     * 清理被吊销 CA 的磁盘私钥文件（*.key.pem.enc / *.key.pem.enc.sealed / 遗留明文）。
     * 只对 ROOT/SUB_CA 类密钥执行；叶证书私钥随证书吊销不删（院内数据恢复场景需要）。
     */
    private void purgeCaKeyFiles(Path dir, KeyVault ov, String serialHex) {
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

    /** 根重建前清理该机构旧根密钥残留：全形态加密文件 + 旧门限份额文件 */
    private void doClearRootKeyMaterial(String orgKey, KeyVault ov) {
        purgeCaKeyFiles(rootDir(orgKey), ov, "ROOT");
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            Path stale = shareFile(orgKey, i);
            try {
                if (Files.deleteIfExists(stale)) {
                    log.info("[根CA][{}] 重建前已删除旧根遗留份额文件: {}", orgKey, stale);
                }
            } catch (IOException e) {
                log.warn("[根CA][{}] 旧份额文件删除失败（不阻断重建）: {}", orgKey, e.getMessage());
            }
        }
    }

    // ==================== SUB_CA（服务签发二级CA） ====================

    /** 确保 SUB_CA 存在（兼容入口=default 机构） */
    public CaCert ensureSubCa() {
        return ensureSubCa(DEFAULT_ORG_KEY);
    }

    /**
     * 确保机构 SUB_CA 存在（幂等，新建/轮换专用）：仅限启动、封存窗口（sealRootKey/initRoot）
     * 与每日预检 {@link #dailyPrecheck()} 调用；日常签发路径不再经过这里。
     */
    public CaCert ensureSubCa(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            return doEnsureSubCa(orgKey, ov, st);
        }
    }

    private CaCert doEnsureSubCa(String orgKey, KeyVault ov, OrgCaState st) {
        CaCert sub = doFindValidSubCa(orgKey);
        if (sub != null && ov.keyExists("subca", "key")) {
            return sub;
        }
        // 根私钥离线（封存/门限分持未解封）时不允许新建/轮换 SUB_CA
        String rkState = doRootKeyState(orgKey, ov);
        if (sub == null && (ROOT_KEY_SEALED.equals(rkState)
                || (ROOT_KEY_SHARES.equals(rkState) && st.rootKeyCache == null))) {
            throw new IllegalStateException("根CA私钥离线（封存/门限分持）且无有效 SUB_CA，请先解封根密钥后轮换签发");
        }
        X509Certificate rootCert = doLoadRootCert(orgKey);
        PrivateKey rootKey = doLoadRootKey(orgKey, ov, st);

        CssslProperties.SubCa subProps = props.getSubCa();
        // SUB_CA 算法跟随该机构根（RSA根→RSA SUB_CA，国密根→SM2 SUB_CA）
        String algorithm = doFindRoot(orgKey).getAlgorithm();
        KeyPair subKey = CertUtil.generateKeyPair(algorithm);
        X509Certificate subCert;
        if (DEFAULT_ORG_KEY.equals(orgKey)) {
            // default 域走旧 CN 入口（DN 含 O=CSSSL），存量证书逐字节兼容
            subCert = CertUtil.issue(subKey, rootCert, rootKey,
                    subProps.getCn(), TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);
        } else {
            CaOrg org = requireOrg(orgKey);
            subCert = CertUtil.issue(CertUtil.subjectDn("CSSSL SubCA - " + org.getOrgName(), org.getSubjectO()),
                    subKey.getPublic(), rootCert, rootKey,
                    TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);
        }

        String serial = subCert.getSerialNumber().toString(16);
        try {
            Files.createDirectories(subCaDir(orgKey));
            Files.writeString(subCaCertFile(orgKey), PemUtil.certToPem(subCert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("SUB_CA落盘失败: " + e.getMessage(), e);
        }
        ov.storeKey("subca", "key", PemUtil.keyToPem(subKey.getPrivate()));

        // 该机构旧 SUB_CA 标记 EXPIRED（由新 SUB_CA 接管在线签发）
        List<CaCert> oldSubs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getOrgKey, orgKey)
                .eq(CaCert::getStatus, "VALID"));
        for (CaCert old : oldSubs) {
            if (!old.getSerialHex().equalsIgnoreCase(serial)) {
                old.setStatus("EXPIRED");
                certMapper.updateById(old);
            }
        }

        CaCert row = new CaCert();
        row.setOrgKey(orgKey);
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
        log.info("[SUB_CA][{}] 已用根签发二级CA：cn={}, algorithm={}, serial={}, 有效期{}天",
                orgKey, row.getSubjectCn(), algorithm, serial, subProps.getValidityDays());
        return row;
    }

    /** 查询当前有效 SUB_CA（兼容入口=default 机构） */
    public CaCert findValidSubCa() {
        return findValidSubCa(DEFAULT_ORG_KEY);
    }

    /** 查询机构当前有效 SUB_CA（未过期且距到期 > renewBeforeDays） */
    public CaCert findValidSubCa(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        return doFindValidSubCa(orgKey);
    }

    private CaCert doFindValidSubCa(String orgKey) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getOrgKey, orgKey)
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
     * 每日到期预检（定时任务，与签发路径解耦）：遍历 default + 全部 ACTIVE 机构逐一预检；
     * 存量单根环境（仅有 default）行为与路线 B 完全一致。
     */
    @Scheduled(cron = "${csssl.subca-precheck-cron:0 30 3 * * ?}")
    public void dailyPrecheck() {
        List<String> keys = new ArrayList<>();
        keys.add(DEFAULT_ORG_KEY);
        try {
            List<CaOrg> orgs = orgMapper.selectList(new LambdaQueryWrapper<CaOrg>()
                    .eq(CaOrg::getStatus, "ACTIVE")
                    .ne(CaOrg::getOrgKey, DEFAULT_ORG_KEY));
            for (CaOrg org : orgs) {
                keys.add(org.getOrgKey());
            }
        } catch (Exception e) {
            log.warn("[SUB_CA] 机构清单读取失败，本轮仅预检 default 域: {}", e.getMessage());
        }
        for (String key : keys) {
            try {
                dailyPrecheck(key);
            } catch (Exception e) {
                log.warn("[SUB_CA][{}] 到期预检异常: {}", key, e.getMessage());
            }
        }
    }

    /**
     * 机构 SUB_CA 到期预检：缺失/进入 renewBeforeDays 续期窗口且根私钥可用（在线或份额已解封）时轮换；
     * 根封存期间只告警，绝不隐式触发需根私钥的操作。
     */
    public void dailyPrecheck(String orgKey) {
        OrgCaState st = stateOf(orgKey);
        KeyVault ov = vaultFor(orgKey);
        synchronized (st.lock) {
            try {
                if (doFindValidSubCa(orgKey) != null && ov.keyExists("subca", "key")) {
                    return;
                }
                String rkState = doRootKeyState(orgKey, ov);
                boolean rootUsable = ROOT_KEY_ONLINE.equals(rkState)
                        || (ROOT_KEY_SHARES.equals(rkState) && st.rootKeyCache != null);
                if (!rootUsable) {
                    log.warn("[SUB_CA][{}] 预检发现 SUB_CA 缺失/临近到期，但根私钥离线（{}），请在根解封窗口轮换", orgKey, rkState);
                    return;
                }
                doEnsureSubCa(orgKey, ov, st);
                log.info("[SUB_CA][{}] 到期预检完成轮换", orgKey);
            } catch (Exception e) {
                log.warn("[SUB_CA][{}] 到期预检失败: {}", orgKey, e.getMessage());
            }
        }
    }

    /** 当前签发CA（兼容入口=default 机构） */
    public X509Certificate signingCert() {
        return signingCert(DEFAULT_ORG_KEY);
    }

    /** 机构当前签发CA（SUB_CA）证书：只取现有有效 SUB_CA，不触发轮换（轮换见 {@link #dailyPrecheck(String)}） */
    public X509Certificate signingCert(String orgKey) {
        return PemUtil.parseCert(doRequireValidSubCa(orgKey).getCertPem());
    }

    public PrivateKey signingKey() {
        return signingKey(DEFAULT_ORG_KEY);
    }

    public PrivateKey signingKey(String orgKey) {
        doRequireValidSubCa(orgKey);
        String keyPem = vaultFor(orgKey).loadKey("subca", "key");
        if (keyPem == null) {
            throw new IllegalStateException("SUB_CA私钥缺失，请在根解封窗口轮换");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    private CaCert doRequireValidSubCa(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        CaCert sub = doFindValidSubCa(orgKey);
        if (sub == null) {
            // 区分两种成因便于运维定位：域内从未建成 SUB_CA（裸根）vs 建成但已过期/被吊销
            throw new IllegalStateException(doCurrentSubCa(orgKey) == null
                    ? "SUB_CA缺失（机构域未初始化完成），请重新调用机构初始化补建"
                    : "SUB_CA已过期，请在根解封窗口轮换");
        }
        return sub;
    }

    /** 当前有效 SUB_CA 记录（兼容入口=default 机构） */
    public CaCert currentSubCa() {
        return currentSubCa(DEFAULT_ORG_KEY);
    }

    /** 机构当前有效 SUB_CA 记录（供链组装/OCSP 使用；无则返回该机构最近一张；均无则 null） */
    public CaCert currentSubCa(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        return doCurrentSubCa(orgKey);
    }

    private CaCert doCurrentSubCa(String orgKey) {
        CaCert sub = doFindValidSubCa(orgKey);
        return sub != null ? sub : certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getOrgKey, orgKey)
                .orderByDesc(CaCert::getCreatedAt)
                .last("limit 1"));
    }

    // ==================== 状态/导出/签发密钥存取 ====================

    /** 根CA状态（兼容入口=default 机构） */
    public CaStatusVo status() {
        return status(DEFAULT_ORG_KEY);
    }

    /** 机构根CA状态（不抛异常，返回未初始化原因供页面展示；含 SUB_CA 与根密钥封存状态） */
    public CaStatusVo status(String orgKey) {
        return doStatus(orgKey, vaultFor(orgKey));
    }

    private CaStatusVo doStatus(String orgKey, KeyVault ov) {
        CaStatusVo vo = new CaStatusVo();
        CaCert root = doFindRoot(orgKey);
        if (root == null) {
            vo.setInitialized(false);
            if (Files.exists(rootCertFile(orgKey)) || ov.keyExists("root", "key")) {
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
        vo.setRootKeyState(doRootKeyState(orgKey, ov));
        vo.setShareState(shareState(orgKey));
        if (ROOT_KEY_MISSING.equals(vo.getRootKeyState())) {
            vo.setInitialized(false);
            vo.setMessage("根CA私钥缺失，无法签发二级CA，请恢复密钥或重建根CA");
        } else {
            vo.setInitialized(true);
        }
        CaCert sub = doCurrentSubCa(orgKey);
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

    /** 根证书 PEM（兼容入口=default 机构） */
    public String exportRootPem() {
        return exportRootPem(DEFAULT_ORG_KEY);
    }

    /** 机构根证书 PEM（业务系统信任链配置用） */
    public String exportRootPem(String orgKey) {
        CaCert root = findRoot(orgKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return root.getCertPem();
    }

    /** 当前 SUB_CA 证书 PEM（兼容入口=default 机构） */
    public String exportSubCaPem() {
        return exportSubCaPem(DEFAULT_ORG_KEY);
    }

    /** 机构当前 SUB_CA 证书 PEM（中间CA链配置用） */
    public String exportSubCaPem(String orgKey) {
        CaCert sub = currentSubCa(orgKey);
        if (sub == null) {
            throw new IllegalStateException("SUB_CA未签发");
        }
        return sub.getCertPem();
    }

    /** 签发证书私钥加密落盘（兼容入口=default 机构） */
    public void storeIssuedKey(String serialHex, String keyPem) {
        storeIssuedKey(DEFAULT_ORG_KEY, serialHex, keyPem);
    }

    /** 机构签发证书私钥加密落盘 */
    public void storeIssuedKey(String orgKey, String serialHex, String keyPem) {
        vaultFor(orgKey).storeKey("issued/" + serialHex, "key", keyPem);
    }

    /** 读取签发证书私钥（兼容入口=default 机构） */
    public String loadIssuedKey(String serialHex) {
        return loadIssuedKey(DEFAULT_ORG_KEY, serialHex);
    }

    /** 读取机构签发证书私钥（无则返回 null；已封存/主密钥错误时抛异常） */
    public String loadIssuedKey(String orgKey, String serialHex) {
        return vaultFor(orgKey).loadKey("issued/" + serialHex, "key");
    }

    public boolean issuedKeyExists(String serialHex) {
        return issuedKeyExists(DEFAULT_ORG_KEY, serialHex);
    }

    public boolean issuedKeyExists(String orgKey, String serialHex) {
        return vaultFor(orgKey).keyExists("issued/" + serialHex, "key");
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
