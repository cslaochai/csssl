package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaTenant;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CaTenantMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.KeyVault;
import com.csssl.util.PemUtil;
import com.csssl.util.ShamirUtil;
import com.csssl.vo.CaStatusVO;
import com.csssl.vo.RootSharesVO;
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
 * <p>路线A多租户（按租户独立根CA）：本类维护 {@code tenantKey → TenantCaState} 根池，
 * 每租户独立的锁、门限解封内存缓存、目录路径与保险库（{@link KeyVaultConfig.TenantVaultResolver}）。
 * 目录规则：default（存量单根，兼容铁律）沿用 store-dir/{root,subca,issued} 与 root/shares、master.key；
 * 新租户为 store-dir/tenants/{tenantKey}/{root,subca,issued,shares} 与 tenants/{tenantKey}/master.key。
 * 所有原无参公开方法委托 default 租户，行为与单根时代完全一致；新代码一律使用带 tenantKey 的版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaService implements ApplicationRunner {

    public static final String TYPE_ROOT = "ROOT";
    public static final String TYPE_SUB_CA = "SUB_CA";

    /**
     * 根私钥状态
     */
    public static final String ROOT_KEY_ONLINE = "ONLINE";
    public static final String ROOT_KEY_SEALED = "SEALED";
    public static final String ROOT_KEY_MISSING = "MISSING";
    /**
     * 根私钥已切换为 Shamir(3,5) 门限分持（份额为唯一持久形态）
     */
    public static final String ROOT_KEY_SHARES = "SHARES";

    /**
     * Shamir 门限参数：总份额 5、恢复阈值 3
     */
    public static final int SHARE_TOTAL = 5;
    public static final int SHARE_THRESHOLD = 3;

    private final CssslProperties props;
    private final CaCertMapper certMapper;
    private final ObjectMapper objectMapper;
    /**
     * 单例保险库 bean（KeyVaultConfig 提供），与 TsaService 共用同一 store-dir 实例；即 default 租户保险库
     */
    private final KeyVault vault;
    /**
     * 租户保险库解析器：default → 上述单例；其他租户 → store-dir/tenants/{tenantKey} 独立保险库
     */
    private final KeyVaultConfig.TenantVaultResolver tenantVaults;
    private final CaTenantMapper tenantMapper;

    /**
     * 租户根CA状态池（computeIfAbsent 懒加载）。每租户一把 per-state 锁：
     * 沿用 B 路线「单一锁对象守护内存密钥读写」的语义，细化到租户粒度——
     * 杜绝"读到刚被封存的私钥"竞态，同时不同租户互不阻塞。
     */
    private final Map<String, TenantCaState> tenantStates = new ConcurrentHashMap<>();

    private TenantCaState stateOf(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        return tenantStates.computeIfAbsent(tenantKey, k -> new TenantCaState());
    }

    // ==================== 租户态/路径/保险库解析 ====================

    /**
     * 租户保险库：default 复用共享单例，其他租户按 tenants/{tenantKey} 根独立（含独立主密钥）
     */
    private KeyVault vaultFor(String tenantKey) {
        return tenantVaults.forTenant(tenantKey);
    }

    /**
     * 租户落盘根目录：default → store-dir；其他 → store-dir/tenants/{tenantKey}
     */
    private Path tenantBaseDir(String tenantKey) {
        return tenantVaults.tenantStoreDir(tenantKey);
    }

    private Path rootDir(String tenantKey) {
        return tenantBaseDir(tenantKey).resolve("root");
    }

    private Path rootCertFile(String tenantKey) {
        return rootDir(tenantKey).resolve("cert.pem");
    }

    private Path subCaDir(String tenantKey) {
        return tenantBaseDir(tenantKey).resolve("subca");
    }

    private Path subCaCertFile(String tenantKey) {
        return subCaDir(tenantKey).resolve("cert.pem");
    }

    /**
     * 签发证书落盘目录（issued/{serial}，兼容入口=default 租户）
     */
    public Path issuedDir(String serialHex) {
        return issuedDir(CaTenant.PRIMARY_TENANT_KEY, serialHex);
    }

    /**
     * 签发证书落盘目录：default → store-dir/issued/{serial}；其他 → store-dir/tenants/{tenantKey}/issued/{serial}
     */
    public Path issuedDir(String tenantKey, String serialHex) {
        return tenantBaseDir(tenantKey).resolve("issued").resolve(serialHex);
    }

    /**
     * 门限份额目录：default 沿用 root/shares（存量路径不动）；新租户按约定平铺在租户目录 shares/
     */
    private Path sharesDir(String tenantKey) {
        return CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)
                ? rootDir(tenantKey).resolve("shares")
                : tenantBaseDir(tenantKey).resolve("shares");
    }

    private Path shareFile(String tenantKey, int index) {
        return sharesDir(tenantKey).resolve("shares-" + index + ".json");
    }

    /**
     * 租户记录（不存在即拒绝操作——路径/密钥材料都锚定注册表，防未登记租户污染磁盘）
     */
    private CaTenant requireTenant(String tenantKey) {
        CaTenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<CaTenant>().eq(CaTenant::getTenantKey, tenantKey));
        if (tenant == null) {
            throw new IllegalStateException("租户不存在: " + tenantKey);
        }
        return tenant;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动仅确保主租户（TenantScope 已校准）；未登记时不代建，交由租户管理页开租户
        String primary = CaTenant.PRIMARY_TENANT_KEY;
        if (tenantMapper.selectOne(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getTenantKey, primary)) == null) {
            log.info("[CA] 主租户 {} 尚未登记，跳过启动初始化（请在租户管理创建租户）", primary);
            return;
        }
        try {
            CaCert root = ensureRoot(primary);
            ensureSubCa(primary);
            // 门限化以「份额目录是否已有完整 5 份份额文件」为唯一判据（ensureRootShares 幂等）：
            // 新根生成或中断后重启都会安全重试，不依赖任何内存标志；已封存/已门限态自动跳过
            if (ROOT_KEY_ONLINE.equals(rootKeyState(primary))) {
                ensureRootShares(primary);
            }
            log.info("[CA] 启动完成（主租户 {}）：root={}({}), subCa 签发就绪，rootKeyState={}",
                    primary, root.getSubjectCn(), root.getAlgorithm(), rootKeyState(primary));
        } catch (Exception e) {
            log.error("[CA] 启动初始化失败，请检查 store-dir 权限、主密钥与数据库连接: {}", e.getMessage());
        }
    }

    /**
     * 确保根CA存在（幂等，兼容入口=主租户）
     */
    public CaCert ensureRoot() {
        return ensureRoot(CaTenant.PRIMARY_TENANT_KEY);
    }

    // ==================== 根CA ====================

    /**
     * 确保指定租户根CA存在（幂等）：优先文件态 → 重建记录 → 全新生成。
     * 根私钥存在性判定兼容两种形态：SM4 加密文件（key.pem.enc）或 Shamir(3,5) 门限份额。
     */
    public CaCert ensureRoot(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(tenantKey);

            if (root != null && (ov.keyExists("root", "key") || doSharesExist(tenantKey))) {
                markExpiredIfNeeded(root);
                return root;
            }
            if (root != null && !ov.keyExists("root", "key") && !doSharesExist(tenantKey)) {
                throw new IllegalStateException("根CA私钥缺失（" + rootDir(tenantKey) + "），无法签发二级CA，请恢复密钥或重建根CA");
            }
            if (root == null && Files.exists(rootCertFile(tenantKey)) && ov.keyExists("root", "key")) {
                CaCert rebuilt = doRebuildFromFiles(tenantKey);
                log.warn("[根CA][{}] 数据库记录缺失，已从落盘文件重建（serial={}）", tenantKey, rebuilt.getSerialHex());
                return rebuilt;
            }
            return doGenerateRoot(tenantKey, ov);
        }
    }

    /**
     * 全新生成根CA（管理端 /admin/ca/init 与启动初始化、开租户流程共用）
     */
    private CaCert doGenerateRoot(String tenantKey, KeyVault ov) {
        String algorithm;
        X509Certificate cert;
        KeyPair keyPair;
        if (CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)) {
            // default 域：CN/算法/有效期全部沿用全局配置与旧4参自签（DN 含 O=CSSSL），存量行为零变化
            CssslProperties.RootCa rootProps = props.getRootCa();
            algorithm = rootProps.getAlgorithm() == null || rootProps.getAlgorithm().isBlank()
                    ? CertUtil.ALG_RSA2048 : rootProps.getAlgorithm().trim();
            CertUtil.checkAlgorithm(algorithm);
            keyPair = CertUtil.generateKeyPair(algorithm);
            cert = CertUtil.selfSignRoot(keyPair, rootProps.getCn(), algorithm, rootProps.getValidityDays());
        } else {
            CaTenant tenant = requireTenant(tenantKey);
            algorithm = tenant.getAlgorithm() == null || tenant.getAlgorithm().isBlank()
                    ? props.getRootCa().getAlgorithm() : tenant.getAlgorithm().trim();
            CertUtil.checkAlgorithm(algorithm);
            keyPair = CertUtil.generateKeyPair(algorithm);
            cert = CertUtil.selfSignRoot(keyPair, "CSSSL ROOT CA - " + tenant.getTenantName(),
                    tenant.getSubjectO(), algorithm, props.getRootCa().getValidityDays());
        }
        String serial = cert.getSerialNumber().toString(16);

        try {
            Files.createDirectories(rootDir(tenantKey));
            Files.writeString(rootCertFile(tenantKey), PemUtil.certToPem(cert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("根CA落盘失败: " + e.getMessage(), e);
        }
        ov.storeKey("root", "key", PemUtil.keyToPem(keyPair.getPrivate()));

        CaCert row = new CaCert();
        row.setTenantKey(tenantKey);
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
        row.setDescription(CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)
                ? "系统根CA（首次启动自动初始化）" : "租户根CA（开租户时初始化）");
        row.setCreatedBy("SYSTEM");
        row.setCreatedAt(LocalDateTime.now());
        certMapper.insert(row);
        // 门限化由调用方（run/initRoot）按"份额文件齐全"判据触发 ensureRootShares，无需内存标志
        log.info("[根CA][{}] 初始化完成：cn={}, algorithm={}, serial={}", tenantKey, row.getSubjectCn(), algorithm, serial);
        return row;
    }

    /**
     * 数据库记录缺失但落盘文件存在时，从文件重建记录
     */
    private CaCert doRebuildFromFiles(String tenantKey) {
        try {
            String certPem = Files.readString(rootCertFile(tenantKey), StandardCharsets.UTF_8);
            X509Certificate cert = PemUtil.parseCert(certPem);
            // 算法探测沿用存量口径（按配置判断 SM2/RSA）；租户域以注册表 algorithm 为准
            String hint = CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)
                    ? props.getRootCa().getAlgorithm() : requireTenant(tenantKey).getAlgorithm();
            CaCert row = new CaCert();
            row.setTenantKey(tenantKey);
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

    /**
     * 查询根CA记录（兼容入口=default 租户）
     */
    public CaCert findRoot() {
        return findRoot(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 查询指定租户根CA记录（serial 全局唯一，租户维度过滤取最新）
     */
    public CaCert findRoot(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        return doFindRoot(tenantKey);
    }

    private CaCert doFindRoot(String tenantKey) {
        List<CaCert> roots = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_ROOT)
                .eq(CaCert::getTenantKey, tenantKey)
                .orderByDesc(CaCert::getCreatedAt));
        return roots.isEmpty() ? null : roots.get(0);
    }

    /**
     * 加载根证书（兼容入口=default 租户）
     */
    public X509Certificate loadRootCert() {
        return loadRootCert(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 加载指定租户根证书
     */
    public X509Certificate loadRootCert(String tenantKey) {
        CaCert root = findRoot(tenantKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /**
     * 加载根CA私钥（兼容入口=default 租户）
     */
    public PrivateKey loadRootKey() {
        return loadRootKey(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 加载租户根证书（内部版，假定调用方已持有租户锁或仅需只读一致性）
     */
    private X509Certificate doLoadRootCert(String tenantKey) {
        CaCert root = doFindRoot(tenantKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /**
     * 加载租户根CA私钥：优先门限解封缓存；否则 SM4 加密文件（旧模式）；份额分持态必须显式解封。
     * 与 seal/unseal/份额迁移同为租户锁方法：读缓存与封存写入互斥，杜绝"读到刚被封存的私钥"。
     */
    public PrivateKey loadRootKey(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            return doLoadRootKey(tenantKey, ov, st);
        }
    }

    private PrivateKey doLoadRootKey(String tenantKey, KeyVault ov, TenantCaState st) {
        if (st.rootKeyCache != null) {
            return st.rootKeyCache;
        }
        CaCert root = doFindRoot(tenantKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        String keyPem = ov.loadKey("root", "key");
        if (keyPem == null) {
            if (doSharesExist(tenantKey)) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请由密钥管理员提交任意 3 份份额解封后再轮换 SUB_CA");
            }
            throw new IllegalStateException("根CA私钥缺失，请恢复密钥或重建根CA");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    /**
     * 根私钥状态（兼容入口=default 租户）
     */
    public String rootKeyState() {
        return rootKeyState(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户根私钥状态：SHARES（门限分持）/ ONLINE / SEALED / MISSING
     */
    public String rootKeyState(String tenantKey) {
        return doRootKeyState(tenantKey, vaultFor(tenantKey));
    }

    private String doRootKeyState(String tenantKey, KeyVault ov) {
        if (doSharesExist(tenantKey)) {
            return ROOT_KEY_SHARES;
        }
        if (ov.isSealed("root", "key")) {
            return ROOT_KEY_SEALED;
        }
        return ov.keyExists("root", "key") ? ROOT_KEY_ONLINE : ROOT_KEY_MISSING;
    }

    /**
     * 封存根私钥（兼容入口=default 租户）
     */
    public void sealRootKey() {
        sealRootKey(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 封存租户根私钥：门限分持态清除内存缓存；旧模式将加密文件移存 .sealed（幂等）
     */
    public void sealRootKey(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(tenantKey);
            if (root == null) {
                throw new IllegalStateException("根CA未初始化，无法封存");
            }
            doEnsureSubCa(tenantKey, ov, st);
            st.rootKeyCache = null;
            if (ov.keyExists("root", "key")) {
                ov.sealKey("root", "key");
            }
            log.info("[根CA][{}] 根私钥已封存（serial={}，门限分持态={}），日常签发由 SUB_CA 承担",
                    tenantKey, root.getSerialHex(), doSharesExist(tenantKey));
        }
    }

    /**
     * 解封根私钥（兼容入口=default 租户）
     */
    public void unsealRootKey() {
        unsealRootKey(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 解封租户根私钥（旧模式移名恢复在线态；门限分持态须走份额解封接口）
     */
    public void unsealRootKey(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            if (doFindRoot(tenantKey) == null) {
                throw new IllegalStateException("根CA未初始化，无法解封");
            }
            if (doSharesExist(tenantKey)) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请用份额解封接口提交任意 3 份");
            }
            ov.unsealKey("root", "key");
            if (ov.isSealed("root", "key")) {
                throw new IllegalStateException("根私钥文件缺失（已封存），无法解封");
            }
            log.info("[根CA][{}] 根私钥已解封，恢复在线态", tenantKey);
        }
    }

    /**
     * 读取单份门限份额 JSON 文本（兼容入口=default 租户）
     */
    public String readShareJson(int index) {
        return readShareJson(CaTenant.PRIMARY_TENANT_KEY, index);
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /**
     * 读取租户单份门限份额 JSON 文本（管理端导出用；不存在抛 IllegalStateException）
     */
    public String readShareJson(String tenantKey, int index) {
        Path file = shareFile(tenantKey, index);
        if (!Files.exists(file)) {
            throw new IllegalStateException("份额文件不存在（编号 " + index + "）：请先执行门限化迁移");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("份额文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 5 份份额是否全部生成（兼容入口=default 租户）
     */
    public boolean sharesExist() {
        return sharesExist(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户 5 份份额是否全部生成（任一缺失视为未完成，避免以不完整份额误判）
     */
    public boolean sharesExist(String tenantKey) {
        return doSharesExist(tenantKey);
    }

    private boolean doSharesExist(String tenantKey) {
        if (!Files.isDirectory(sharesDir(tenantKey))) {
            return false;
        }
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            if (!Files.exists(shareFile(tenantKey, i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 份额状态（兼容入口=default 租户）
     */
    public RootSharesVO shareState() {
        return shareState(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户份额状态（管理端展示：5 份各自存在性 + 阈值）
     */
    public RootSharesVO shareState(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        List<RootSharesVO.ShareItem> shares = new ArrayList<>();
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            RootSharesVO.ShareItem item = new RootSharesVO.ShareItem();
            item.setIndex(i);
            item.setExists(Files.exists(shareFile(tenantKey, i)));
            shares.add(item);
        }
        RootSharesVO vo = new RootSharesVO();
        vo.setGenerated(doSharesExist(tenantKey));
        vo.setTotal(SHARE_TOTAL);
        vo.setThreshold(SHARE_THRESHOLD);
        vo.setShares(shares);
        return vo;
    }

    /**
     * 根私钥门限化（兼容入口=default 租户）
     */
    public void ensureRootShares() {
        ensureRootShares(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户根私钥门限化（幂等）：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 仅当 5 份份额全部成功落盘后才删除原始文件；任何一份失败则保留原始文件并报错（不静默降级）。
     * 触发：run()/initRoot() 以「份额文件是否齐全」为唯一判据调用（中断/重启后可安全重试）；旧环境也可由管理员显式调用迁移接口。
     */
    public void ensureRootShares(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            doEnsureRootShares(tenantKey, ov, st);
        }
    }

    private void doEnsureRootShares(String tenantKey, KeyVault ov, TenantCaState st) {
        if (doSharesExist(tenantKey)) {
            return;
        }
        CaCert root = doFindRoot(tenantKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法生成门限份额");
        }
        String keyPem = ov.loadKey("root", "key");
        if (keyPem == null) {
            throw new IllegalStateException("根私钥不可用（无 SM4 加密副本可拆分），无法生成门限份额");
        }
        try {
            Files.createDirectories(sharesDir(tenantKey));
            PrivateKey key = PemUtil.parsePrivateKey(keyPem);
            ShamirUtil.Share[] shares = ShamirUtil.split(key.getEncoded(), SHARE_TOTAL, SHARE_THRESHOLD);
            for (ShamirUtil.Share s : shares) {
                Files.writeString(shareFile(tenantKey, s.index()),
                        objectMapper.writeValueAsString(s), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("门限份额生成失败（原始根私钥未删除）: " + e.getMessage(), e);
        }
        // 5 份份额全部落盘成功后才删除单点加密文件
        if (doSharesExist(tenantKey)) {
            ov.deleteKey("root", "key");
            st.rootKeyCache = null;
            log.warn("[根CA][{}] 根私钥已切换为 Shamir(3,5) 门限分持（serial={}）：5 份份额落盘，原始加密文件已删除，恢复需任意 3 份",
                    tenantKey, root.getSerialHex());
        } else {
            throw new IllegalStateException("份额写入不完整，已保留原始根私钥，请检查份额目录后重试");
        }
    }

    /**
     * 旧环境迁移（兼容入口=default 租户）
     */
    public RootSharesVO migrateRootShares() {
        return migrateRootShares(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户迁移：把已存在的 SM4 加密根私钥转为门限份额（显式调用，避免误删生产密钥）
     */
    public RootSharesVO migrateRootShares(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            if (doSharesExist(tenantKey)) {
                return shareState(tenantKey);
            }
            doEnsureRootShares(tenantKey, ov, st);
            return shareState(tenantKey);
        }
    }

    /**
     * 份额解封（兼容入口=default 租户）
     */
    public void unsealRootKeyWithShares(List<String> shareJsons) {
        unsealRootKeyWithShares(CaTenant.PRIMARY_TENANT_KEY, shareJsons);
    }

    /**
     * 租户份额解封：提交任意 3 份门限份额，重组根私钥并置入该租户内存缓存（仅本次进程有效）。
     * 重组结果用该租户根证书公钥做签名挑战校验，防止份额被篡改/错配。
     */
    public void unsealRootKeyWithShares(String tenantKey, List<String> shareJsons) {
        TenantCaState st = stateOf(tenantKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(tenantKey);
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
                log.warn("[根CA][{}] 已通过 {} 份门限份额解封根私钥（内存缓存），使用后请及时封存", tenantKey, shares.size());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("份额解封失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 用根证书公钥校验重组私钥匹配（签名挑战，防份额错配/篡改；份额天然按租户目录存放，跨租户份额无法互用）
     */
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

    /**
     * 管理端主动初始化（兼容入口=default 租户）
     */
    public CaStatusVO initRoot() {
        return initRoot(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户根CA初始化：已就绪则原样返回；根私钥丢失则吊销旧根并重建（连带吊销旧SUB_CA）。
     * default 域保持存量 CN/算法配置；新租户按注册表 algorithm/subject_o 生成独立根。
     */
    public CaStatusVO initRoot(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            CaCert root = doFindRoot(tenantKey);
            if (root != null && (ov.keyExists("root", "key") || doSharesExist(tenantKey))) {
                // 幂等修复路径：首建中途失败会留下"有根无SUB_CA"的裸域，此处补建（已有有效SUB_CA即直接复用）
                doEnsureSubCa(tenantKey, ov, st);
                return doStatus(tenantKey, ov);
            }
            if (root != null) {
                doRevokeAllSubCa(tenantKey, ov, "ROOT_KEY_LOST", root.getSubjectCn());
                root.setStatus("REVOKED");
                root.setRevokeReason("ROOT_KEY_LOST");
                root.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(root);
                // 清理旧根密钥残留（含半套旧份额）：防遗留份额文件被"份额齐全"判据误命中、跳过新根门限化
                doClearRootKeyMaterial(tenantKey, ov);
                log.warn("[根CA][{}] 旧根私钥缺失，已吊销（serial={}）并重建新根", tenantKey, root.getSerialHex());
            }
            CaCert created = doGenerateRoot(tenantKey, ov);
            doEnsureSubCa(tenantKey, ov, st);
            // 新根自动切换为 Shamir(3,5) 门限分持（删单点加密文件）
            doEnsureRootShares(tenantKey, ov, st);
            CaStatusVO vo = doStatus(tenantKey, ov);
            vo.setMessage("根CA已重建：" + created.getSubjectCn() + "（序列号 " + created.getSerialHex() + "），已签发新SUB_CA，根私钥已切换为 Shamir(3,5) 门限分持（请尽快导存 5 份份额）");
            return vo;
        }
    }

    /**
     * 吊销该租户全部有效 SUB_CA（根重建/根丢失时调用，旧链整体失效），并清理其磁盘私钥（仅 CA 类密钥）
     */
    private void doRevokeAllSubCa(String tenantKey, KeyVault ov, String reason, String rootCn) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getTenantKey, tenantKey));
        for (CaCert sub : subs) {
            if (!"REVOKED".equals(sub.getStatus())) {
                sub.setStatus("REVOKED");
                sub.setRevokeReason(reason);
                sub.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(sub);
                purgeCaKeyFiles(subCaDir(tenantKey), ov, sub.getSerialHex());
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

    /**
     * 根重建前清理该租户旧根密钥残留：全形态加密文件 + 旧门限份额文件
     */
    private void doClearRootKeyMaterial(String tenantKey, KeyVault ov) {
        purgeCaKeyFiles(rootDir(tenantKey), ov, "ROOT");
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            Path stale = shareFile(tenantKey, i);
            try {
                if (Files.deleteIfExists(stale)) {
                    log.info("[根CA][{}] 重建前已删除旧根遗留份额文件: {}", tenantKey, stale);
                }
            } catch (IOException e) {
                log.warn("[根CA][{}] 旧份额文件删除失败（不阻断重建）: {}", tenantKey, e.getMessage());
            }
        }
    }

    /**
     * 确保 SUB_CA 存在（兼容入口=default 租户）
     */
    public CaCert ensureSubCa() {
        return ensureSubCa(CaTenant.PRIMARY_TENANT_KEY);
    }

    // ==================== SUB_CA（服务签发二级CA） ====================

    /**
     * 确保租户 SUB_CA 存在（幂等，新建/轮换专用）：仅限启动、封存窗口（sealRootKey/initRoot）
     * 与每日预检 {@link #dailyPrecheck()} 调用；日常签发路径不再经过这里。
     */
    public CaCert ensureSubCa(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            return doEnsureSubCa(tenantKey, ov, st);
        }
    }

    private CaCert doEnsureSubCa(String tenantKey, KeyVault ov, TenantCaState st) {
        CaCert sub = doFindValidSubCa(tenantKey);
        if (sub != null && ov.keyExists("subca", "key")) {
            return sub;
        }
        // 根私钥离线（封存/门限分持未解封）时不允许新建/轮换 SUB_CA
        String rkState = doRootKeyState(tenantKey, ov);
        if (sub == null && (ROOT_KEY_SEALED.equals(rkState)
                || (ROOT_KEY_SHARES.equals(rkState) && st.rootKeyCache == null))) {
            throw new IllegalStateException("根CA私钥离线（封存/门限分持）且无有效 SUB_CA，请先解封根密钥后轮换签发");
        }
        X509Certificate rootCert = doLoadRootCert(tenantKey);
        PrivateKey rootKey = doLoadRootKey(tenantKey, ov, st);

        CssslProperties.SubCa subProps = props.getSubCa();
        // SUB_CA 算法跟随该租户根（RSA根→RSA SUB_CA，国密根→SM2 SUB_CA）
        String algorithm = doFindRoot(tenantKey).getAlgorithm();
        KeyPair subKey = CertUtil.generateKeyPair(algorithm);
        X509Certificate subCert;
        if (CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)) {
            // default 域走旧 CN 入口（DN 含 O=CSSSL），存量证书逐字节兼容
            subCert = CertUtil.issue(subKey, rootCert, rootKey,
                    subProps.getCn(), TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);
        } else {
            CaTenant tenant = requireTenant(tenantKey);
            subCert = CertUtil.issue(CertUtil.subjectDn("CSSSL SubCA - " + tenant.getTenantName(), tenant.getSubjectO()),
                    subKey.getPublic(), rootCert, rootKey,
                    TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);
        }

        String serial = subCert.getSerialNumber().toString(16);
        try {
            Files.createDirectories(subCaDir(tenantKey));
            Files.writeString(subCaCertFile(tenantKey), PemUtil.certToPem(subCert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("SUB_CA落盘失败: " + e.getMessage(), e);
        }
        ov.storeKey("subca", "key", PemUtil.keyToPem(subKey.getPrivate()));

        // 该租户旧 SUB_CA 标记 EXPIRED（由新 SUB_CA 接管在线签发）
        List<CaCert> oldSubs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getTenantKey, tenantKey)
                .eq(CaCert::getStatus, "VALID"));
        for (CaCert old : oldSubs) {
            if (!old.getSerialHex().equalsIgnoreCase(serial)) {
                old.setStatus("EXPIRED");
                certMapper.updateById(old);
            }
        }

        CaCert row = new CaCert();
        row.setTenantKey(tenantKey);
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
                tenantKey, row.getSubjectCn(), algorithm, serial, subProps.getValidityDays());
        return row;
    }

    /**
     * 查询当前有效 SUB_CA（兼容入口=default 租户）
     */
    public CaCert findValidSubCa() {
        return findValidSubCa(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 查询租户当前有效 SUB_CA（未过期且距到期 > renewBeforeDays）
     */
    public CaCert findValidSubCa(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        return doFindValidSubCa(tenantKey);
    }

    private CaCert doFindValidSubCa(String tenantKey) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getTenantKey, tenantKey)
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
     * 每日到期预检（定时任务，与签发路径解耦）：遍历 default + 全部 ACTIVE 租户逐一预检；
     * 存量单根环境（仅有 default）行为与路线 B 完全一致。
     */
    @Scheduled(cron = "${csssl.subca-precheck-cron:0 30 3 * * ?}")
    public void dailyPrecheck() {
        List<String> keys = new ArrayList<>();
        keys.add(CaTenant.PRIMARY_TENANT_KEY);
        try {
            List<CaTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<CaTenant>()
                    .eq(CaTenant::getStatus, "ACTIVE")
                    .ne(CaTenant::getTenantKey, CaTenant.PRIMARY_TENANT_KEY));
            for (CaTenant tenant : tenants) {
                keys.add(tenant.getTenantKey());
            }
        } catch (Exception e) {
            log.warn("[SUB_CA] 租户清单读取失败，本轮仅预检 default 域: {}", e.getMessage());
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
     * 租户 SUB_CA 到期预检：缺失/进入 renewBeforeDays 续期窗口且根私钥可用（在线或份额已解封）时轮换；
     * 根封存期间只告警，绝不隐式触发需根私钥的操作。
     */
    public void dailyPrecheck(String tenantKey) {
        TenantCaState st = stateOf(tenantKey);
        KeyVault ov = vaultFor(tenantKey);
        synchronized (st.lock) {
            try {
                if (doFindValidSubCa(tenantKey) != null && ov.keyExists("subca", "key")) {
                    return;
                }
                String rkState = doRootKeyState(tenantKey, ov);
                boolean rootUsable = ROOT_KEY_ONLINE.equals(rkState)
                        || (ROOT_KEY_SHARES.equals(rkState) && st.rootKeyCache != null);
                if (!rootUsable) {
                    log.warn("[SUB_CA][{}] 预检发现 SUB_CA 缺失/临近到期，但根私钥离线（{}），请在根解封窗口轮换", tenantKey, rkState);
                    return;
                }
                doEnsureSubCa(tenantKey, ov, st);
                log.info("[SUB_CA][{}] 到期预检完成轮换", tenantKey);
            } catch (Exception e) {
                log.warn("[SUB_CA][{}] 到期预检失败: {}", tenantKey, e.getMessage());
            }
        }
    }

    /**
     * 当前签发CA（兼容入口=default 租户）
     */
    public X509Certificate signingCert() {
        return signingCert(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户当前签发CA（SUB_CA）证书：只取现有有效 SUB_CA，不触发轮换（轮换见 {@link #dailyPrecheck(String)}）
     */
    public X509Certificate signingCert(String tenantKey) {
        return PemUtil.parseCert(doRequireValidSubCa(tenantKey).getCertPem());
    }

    public PrivateKey signingKey() {
        return signingKey(CaTenant.PRIMARY_TENANT_KEY);
    }

    public PrivateKey signingKey(String tenantKey) {
        doRequireValidSubCa(tenantKey);
        String keyPem = vaultFor(tenantKey).loadKey("subca", "key");
        if (keyPem == null) {
            throw new IllegalStateException("SUB_CA私钥缺失，请在根解封窗口轮换");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    private CaCert doRequireValidSubCa(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        CaCert sub = doFindValidSubCa(tenantKey);
        if (sub == null) {
            // 区分两种成因便于运维定位：域内从未建成 SUB_CA（裸根）vs 建成但已过期/被吊销
            throw new IllegalStateException(doCurrentSubCa(tenantKey) == null
                    ? "SUB_CA缺失（租户域未初始化完成），请重新调用租户初始化补建"
                    : "SUB_CA已过期，请在根解封窗口轮换");
        }
        return sub;
    }

    /**
     * 当前有效 SUB_CA 记录（兼容入口=default 租户）
     */
    public CaCert currentSubCa() {
        return currentSubCa(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户当前有效 SUB_CA 记录（供链组装/OCSP 使用；无则返回该租户最近一张；均无则 null）
     */
    public CaCert currentSubCa(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        return doCurrentSubCa(tenantKey);
    }

    private CaCert doCurrentSubCa(String tenantKey) {
        CaCert sub = doFindValidSubCa(tenantKey);
        return sub != null ? sub : certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getTenantKey, tenantKey)
                .orderByDesc(CaCert::getCreatedAt)
                .last("limit 1"));
    }

    /**
     * 根CA状态（兼容入口=default 租户）
     */
    public CaStatusVO status() {
        return status(CaTenant.PRIMARY_TENANT_KEY);
    }

    // ==================== 状态/导出/签发密钥存取 ====================

    /**
     * 租户根CA状态（不抛异常，返回未初始化原因供页面展示；含 SUB_CA 与根密钥封存状态）
     */
    public CaStatusVO status(String tenantKey) {
        return doStatus(tenantKey, vaultFor(tenantKey));
    }

    private CaStatusVO doStatus(String tenantKey, KeyVault ov) {
        CaStatusVO vo = new CaStatusVO();
        CaCert root = doFindRoot(tenantKey);
        if (root == null) {
            vo.setInitialized(false);
            if (Files.exists(rootCertFile(tenantKey)) || ov.keyExists("root", "key")) {
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
        vo.setRootKeyState(doRootKeyState(tenantKey, ov));
        vo.setShareState(shareState(tenantKey));
        if (ROOT_KEY_MISSING.equals(vo.getRootKeyState())) {
            vo.setInitialized(false);
            vo.setMessage("根CA私钥缺失，无法签发二级CA，请恢复密钥或重建根CA");
        } else {
            vo.setInitialized(true);
        }
        CaCert sub = doCurrentSubCa(tenantKey);
        if (sub != null) {
            CaStatusVO.SubCaStatus subVo = new CaStatusVO.SubCaStatus();
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

    /**
     * 根证书 PEM（兼容入口=default 租户）
     */
    public String exportRootPem() {
        return exportRootPem(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户根证书 PEM（业务系统信任链配置用）
     */
    public String exportRootPem(String tenantKey) {
        CaCert root = findRoot(tenantKey);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return root.getCertPem();
    }

    /**
     * 当前 SUB_CA 证书 PEM（兼容入口=default 租户）
     */
    public String exportSubCaPem() {
        return exportSubCaPem(CaTenant.PRIMARY_TENANT_KEY);
    }

    /**
     * 租户当前 SUB_CA 证书 PEM（中间CA链配置用）
     */
    public String exportSubCaPem(String tenantKey) {
        CaCert sub = currentSubCa(tenantKey);
        if (sub == null) {
            throw new IllegalStateException("SUB_CA未签发");
        }
        return sub.getCertPem();
    }

    /**
     * 签发证书私钥加密落盘（兼容入口=default 租户）
     */
    public void storeIssuedKey(String serialHex, String keyPem) {
        storeIssuedKey(CaTenant.PRIMARY_TENANT_KEY, serialHex, keyPem);
    }

    /**
     * 租户签发证书私钥加密落盘
     */
    public void storeIssuedKey(String tenantKey, String serialHex, String keyPem) {
        vaultFor(tenantKey).storeKey("issued/" + serialHex, "key", keyPem);
    }

    /**
     * 读取签发证书私钥（兼容入口=default 租户）
     */
    public String loadIssuedKey(String serialHex) {
        return loadIssuedKey(CaTenant.PRIMARY_TENANT_KEY, serialHex);
    }

    /**
     * 读取租户签发证书私钥（无则返回 null；已封存/主密钥错误时抛异常）
     */
    public String loadIssuedKey(String tenantKey, String serialHex) {
        return vaultFor(tenantKey).loadKey("issued/" + serialHex, "key");
    }

    public boolean issuedKeyExists(String serialHex) {
        return issuedKeyExists(CaTenant.PRIMARY_TENANT_KEY, serialHex);
    }

    public boolean issuedKeyExists(String tenantKey, String serialHex) {
        return vaultFor(tenantKey).keyExists("issued/" + serialHex, "key");
    }

    /**
     * 证书过期后自动标记 EXPIRED（仅状态展示，不参与吊销）
     */
    private void markExpiredIfNeeded(CaCert cert) {
        if ("VALID".equals(cert.getStatus()) && cert.getValidTo() != null
                && cert.getValidTo().isBefore(LocalDateTime.now())) {
            cert.setStatus("EXPIRED");
            certMapper.updateById(cert);
        }
    }

    private static final class TenantCaState {
        /**
         * 租户锁对象：该租户 rootKeyCache 及根/子CA密钥读写的唯一互斥域
         */
        final Object lock = new Object();
        /**
         * 份额解封后的临时根私钥（仅内存，用后 seal 清除）。
         * 所有读写（loadRootKey / seal / unseal / 份额迁移 / ensureSubCa 离线判定）统一持本租户锁。
         */
        PrivateKey rootKeyCache;
    }
}
