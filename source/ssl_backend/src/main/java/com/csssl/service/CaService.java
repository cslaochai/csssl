package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaCompany;
import com.csssl.entity.KeyVaultEntry;
import com.csssl.entity.SysUser;
import com.csssl.entity.CompanyKeyShare;
import com.csssl.enums.RoleCodeEnum;
import com.csssl.enums.CompanyStatusEnum;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CompanyMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.mapper.CompanyKeyShareMapper;
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
import org.springframework.transaction.annotation.Transactional;

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
 *   <li>私钥存储策略（刻意区分）：根CA私钥不落库——SM4 加密后仅落盘 root/key.pem.enc
 *       （封存态 key.pem.enc.sealed），门限分持就绪后该单点文件删除、仅余份额文件，杜绝"单库被拖走即失守"；
 *       二级CA与签发证书私钥仍经 {@link KeyVault} 落库 ssl_key_vault（SM4），数据库证书表只存证书与元信息。</li>
 * </ul>
 *
 * <p>路线A多公司（按公司独立根CA）：本类维护 {@code companyUscc → CompanyCaState} 根池，
 * 每公司独立的锁、门限解封内存缓存、目录路径与保险库（{@link KeyVaultConfig.CompanyVaultResolver}）。
 * 目录规则：default（存量单根，兼容铁律）沿用 store-dir/{root,subca,issued} 与 root/shares、master.key；
 * 新公司为 store-dir/companies/{companyUscc}/{root,subca,issued,shares} 与 companies/{companyUscc}/master.key。
 * 所有原无参公开方法委托 default 公司，行为与单根时代完全一致；新代码一律使用带 companyUscc 的版本。</p>
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
     * 单例保险库 bean（KeyVaultConfig 提供），与 TsaService 共用同一 store-dir 实例；即 default 公司保险库
     */
    private final KeyVault vault;
    /**
     * 公司保险库解析器：default → 上述单例；其他公司 → store-dir/companies/{companyUscc} 独立保险库
     */
    private final KeyVaultConfig.CompanyVaultResolver companyVaults;
    private final CompanyMapper companyMapper;
    private final SysUserMapper sysUserMapper;
    private final CompanyKeyShareMapper keyShareMapper;

    /**
     * 公司根CA状态池（computeIfAbsent 懒加载）。每公司一把 per-state 锁：
     * 沿用 B 路线「单一锁对象守护内存密钥读写」的语义，细化到公司粒度——
     * 杜绝"读到刚被封存的私钥"竞态，同时不同公司互不阻塞。
     */
    private final Map<String, CompanyCaState> companyStates = new ConcurrentHashMap<>();

    private CompanyCaState stateOf(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        return companyStates.computeIfAbsent(companyUscc, k -> new CompanyCaState());
    }

    // ==================== 公司态/路径/保险库解析 ====================

    /**
     * 公司保险库：default 复用共享单例，其他公司按 companies/{companyUscc} 根独立（含独立主密钥）
     */
    private KeyVault vaultFor(String companyUscc) {
        return companyVaults.forCompany(companyUscc);
    }

    /**
     * 公司落盘根目录：default → store-dir；其他 → store-dir/companies/{companyUscc}
     */
    private Path companyBaseDir(String companyUscc) {
        return companyVaults.companyStoreDir(companyUscc);
    }

    private Path rootDir(String companyUscc) {
        return companyBaseDir(companyUscc).resolve("root");
    }

    private Path rootCertFile(String companyUscc) {
        return rootDir(companyUscc).resolve("cert.pem");
    }

    private Path subCaDir(String companyUscc) {
        return companyBaseDir(companyUscc).resolve("subca");
    }

    private Path subCaCertFile(String companyUscc) {
        return subCaDir(companyUscc).resolve("cert.pem");
    }

    /**
     * 签发证书落盘目录（issued/{serial}，兼容入口=default 公司）
     */
    public Path issuedDir(String serialHex) {
        return issuedDir(CaCompany.PRIMARY_COMPANY_USCC, serialHex);
    }

    /**
     * 签发证书落盘目录：default → store-dir/issued/{serial}；其他 → store-dir/companies/{companyUscc}/issued/{serial}
     */
    public Path issuedDir(String companyUscc, String serialHex) {
        return companyBaseDir(companyUscc).resolve("issued").resolve(serialHex);
    }

    /**
     * 门限份额目录：default 沿用 root/shares（存量路径不动）；新公司按约定平铺在公司目录 shares/
     */
    private Path sharesDir(String companyUscc) {
        return CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                ? rootDir(companyUscc).resolve("shares")
                : companyBaseDir(companyUscc).resolve("shares");
    }

    private Path shareFile(String companyUscc, int index) {
        return sharesDir(companyUscc).resolve("shares-" + index + ".json");
    }

    // ==================== 根私钥文件化（不落库，仅落盘 root/key.pem.enc / .sealed） ====================
    // 设计取舍：根CA私钥刻意不进 ssl_key_vault——单库被整库拖走即失守。改为同公司主密钥 SM4 加密后
    // 落盘 root/key.pem.enc（封存态 key.pem.enc.sealed），门限分持就绪后该单点文件删除、仅余份额文件。
    // 二级CA与签发证书私钥仍走 KeyVault 落库（与既有安全策略一致，用户明确允许）。

    /** 根私钥加密文件（SM4，同公司主密钥） */
    private static final String ROOT_KEY_ENC_FILE = "key.pem.enc";
    /** 根私钥封存态文件（离线化，语义等同原 .sealed 改名） */
    private static final String ROOT_KEY_SEALED_FILE = "key.pem.enc.sealed";

    private Path rootKeyEncPath(String companyUscc) {
        return rootDir(companyUscc).resolve(ROOT_KEY_ENC_FILE);
    }

    private Path rootKeySealedPath(String companyUscc) {
        return rootDir(companyUscc).resolve(ROOT_KEY_SEALED_FILE);
    }

    /** 根私钥是否以单点加密文件形态存在（在线或封存，未门限分持） */
    private boolean rootKeyFileExists(String companyUscc) {
        return Files.exists(rootKeyEncPath(companyUscc)) || Files.exists(rootKeySealedPath(companyUscc));
    }

    /** 根私钥是否已封存（离线） */
    private boolean rootKeyFileSealed(String companyUscc) {
        return Files.exists(rootKeySealedPath(companyUscc));
    }

    /** 根私钥 SM4 加密落盘（root/key.pem.enc），全程不落库 */
    private void storeRootKeyFile(String companyUscc, String keyPem) {
        try {
            Files.createDirectories(rootDir(companyUscc));
            String cipher = vaultFor(companyUscc).encryptHex(keyPem);
            Files.writeString(rootKeyEncPath(companyUscc), cipher, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("根私钥落盘失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取根私钥 PEM：封存态抛明确异常（须先解封）；不存在返回 null（交由调用方判门限/缺失）。
     */
    private String loadRootKeyFile(String companyUscc) {
        if (rootKeyFileSealed(companyUscc)) {
            throw new IllegalStateException("根私钥已封存离线（" + companyUscc + "/root），请先在管理端解封");
        }
        Path enc = rootKeyEncPath(companyUscc);
        if (!Files.exists(enc)) {
            return null;
        }
        try {
            String cipher = Files.readString(enc, StandardCharsets.UTF_8).trim();
            return vaultFor(companyUscc).decryptStr(cipher);
        } catch (IOException e) {
            throw new IllegalStateException("根私钥文件读取失败: " + e.getMessage(), e);
        }
    }

    /** 封存根私钥：key.pem.enc → key.pem.enc.sealed */
    private void sealRootKeyFile(String companyUscc) {
        Path enc = rootKeyEncPath(companyUscc);
        if (Files.exists(enc)) {
            try {
                Files.move(enc, rootKeySealedPath(companyUscc));
            } catch (IOException e) {
                throw new IllegalStateException("根私钥封存失败: " + e.getMessage(), e);
            }
        }
    }

    /** 解封根私钥：key.pem.enc.sealed → key.pem.enc */
    private void unsealRootKeyFile(String companyUscc) {
        Path sealed = rootKeySealedPath(companyUscc);
        if (Files.exists(sealed)) {
            try {
                Files.move(sealed, rootKeyEncPath(companyUscc));
            } catch (IOException e) {
                throw new IllegalStateException("根私钥解封失败: " + e.getMessage(), e);
            }
        }
    }

    /** 删除根私钥全部单点文件形态（门限分持就绪后调用） */
    private void deleteRootKeyFile(String companyUscc) {
        try {
            Files.deleteIfExists(rootKeyEncPath(companyUscc));
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(rootKeySealedPath(companyUscc));
        } catch (IOException ignored) {
        }
    }

    /**
     * 兼容升级：旧版根私钥可能落库 ssl_key_vault，迁移为单文件形态后清除库内条目（全程不落库）。
     * 已文件化或库内无条目则跳过；保留封存/在线态。
     */
    private void migrateRootKeyFromDbIfNeeded(String companyUscc, KeyVault ov) {
        if (rootKeyFileExists(companyUscc)) {
            return;
        }
        KeyVaultEntry entry = ov.peekEntry("root", "key");
        if (entry == null) {
            return;
        }
        try {
            Files.createDirectories(rootDir(companyUscc));
            Path target = (entry.getSealed() != null && entry.getSealed() == 1)
                    ? rootKeySealedPath(companyUscc) : rootKeyEncPath(companyUscc);
            Files.writeString(target, entry.getCipherHex(), StandardCharsets.UTF_8);
            ov.deleteDbEntry("root", "key");
            log.warn("[根CA][{}] 已将根私钥从 ssl_key_vault 迁移为单文件形态（{}），不再落库",
                    companyUscc, entry.getSealed() != null && entry.getSealed() == 1 ? "封存态" : "在线态");
        } catch (IOException e) {
            throw new IllegalStateException("根私钥库→文件迁移失败: " + e.getMessage(), e);
        }
    }

    /**
     * 公司记录（不存在即拒绝操作——路径/密钥材料都锚定注册表，防未登记公司污染磁盘）
     */
    private CaCompany requireCompany(String companyUscc) {
        CaCompany company = companyMapper.selectOne(new LambdaQueryWrapper<CaCompany>().eq(CaCompany::getUscc, companyUscc));
        if (company == null) {
            throw new IllegalStateException("公司不存在: " + companyUscc);
        }
        return company;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动仅确保主公司（CompanyScope 已校准）；未登记时不代建，交由公司管理页开公司
        String primary = CaCompany.PRIMARY_COMPANY_USCC;
        if (companyMapper.selectOne(new LambdaQueryWrapper<CaCompany>()
                .eq(CaCompany::getUscc, primary)) == null) {
            log.info("[CA] 主公司 {} 尚未登记，跳过启动初始化（请在公司管理创建公司）", primary);
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
            log.info("[CA] 启动完成（主公司 {}）：root={}({}), subCa 签发就绪，rootKeyState={}",
                    primary, root.getSubjectCn(), root.getAlgorithm(), rootKeyState(primary));
        } catch (Exception e) {
            log.error("[CA] 启动初始化失败，请检查 store-dir 权限、主密钥与数据库连接: {}", e.getMessage());
        }
    }

    /**
     * 确保根CA存在（幂等，兼容入口=主公司）
     */
    public CaCert ensureRoot() {
        return ensureRoot(CaCompany.PRIMARY_COMPANY_USCC);
    }

    // ==================== 根CA ====================

    /**
     * 确保指定公司根CA存在（幂等）：优先文件态 → 重建记录 → 全新生成。
     * 根私钥存在性判定兼容两种形态：SM4 加密文件（key.pem.enc）或 Shamir(3,5) 门限份额。
     */
    public CaCert ensureRoot(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            migrateRootKeyFromDbIfNeeded(companyUscc, ov);
            CaCert root = doFindRoot(companyUscc);

            if (root != null && (rootKeyFileExists(companyUscc) || doSharesExist(companyUscc))) {
                markExpiredIfNeeded(root);
                return root;
            }
            if (root != null && !rootKeyFileExists(companyUscc) && !doSharesExist(companyUscc)) {
                throw new IllegalStateException("根CA私钥缺失（" + rootDir(companyUscc) + "），无法签发二级CA，请恢复密钥或重建根CA");
            }
            if (root == null && Files.exists(rootCertFile(companyUscc))
                    && (rootKeyFileExists(companyUscc) || doSharesExist(companyUscc))) {
                CaCert rebuilt = doRebuildFromFiles(companyUscc);
                log.warn("[根CA][{}] 数据库记录缺失，已从落盘文件重建（serial={}）", companyUscc, rebuilt.getSerialHex());
                return rebuilt;
            }
            return doGenerateRoot(companyUscc, ov);
        }
    }

    /**
     * 全新生成根CA（管理端 /admin/ca/init 与启动初始化、开公司流程共用）
     */
    private CaCert doGenerateRoot(String companyUscc, KeyVault ov) {
        String algorithm;
        X509Certificate cert;
        KeyPair keyPair;
        if (CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            // default 域：CN/算法/有效期全部沿用全局配置与旧4参自签（DN 含 O=CSSSL），存量行为零变化
            CssslProperties.RootCa rootProps = props.getRootCa();
            algorithm = rootProps.getAlgorithm() == null || rootProps.getAlgorithm().isBlank()
                    ? CertUtil.ALG_RSA2048 : rootProps.getAlgorithm().trim();
            CertUtil.checkAlgorithm(algorithm);
            keyPair = CertUtil.generateKeyPair(algorithm);
            cert = CertUtil.selfSignRoot(keyPair, rootProps.getCn(), algorithm, rootProps.getValidityDays());
        } else {
            CaCompany company = requireCompany(companyUscc);
            algorithm = company.getAlgorithm() == null || company.getAlgorithm().isBlank()
                    ? props.getRootCa().getAlgorithm() : company.getAlgorithm().trim();
            CertUtil.checkAlgorithm(algorithm);
            keyPair = CertUtil.generateKeyPair(algorithm);
            cert = CertUtil.selfSignRoot(keyPair, "CSSSL ROOT CA - " + company.getCompanyName(),
                    company.getSubjectO(), algorithm, props.getRootCa().getValidityDays(), company.getUscc());
        }
        String serial = cert.getSerialNumber().toString(16);

        try {
            Files.createDirectories(rootDir(companyUscc));
            Files.writeString(rootCertFile(companyUscc), PemUtil.certToPem(cert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("根CA落盘失败: " + e.getMessage(), e);
        }
        // 根私钥刻意不落库：SM4 加密后落盘 root/key.pem.enc（与二级CA/签发证书走库的策略分离）
        storeRootKeyFile(companyUscc, PemUtil.keyToPem(keyPair.getPrivate()));

        CaCert row = new CaCert();
        row.setCompanyUscc(companyUscc);
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
        row.setDescription(CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                ? "系统根CA（首次启动自动初始化）" : "公司根CA（开公司时初始化）");
        row.setCreatedAt(LocalDateTime.now());
        certMapper.insert(row);
        // 门限化由调用方（run/initRoot）按"份额文件齐全"判据触发 ensureRootShares，无需内存标志
        log.info("[根CA][{}] 初始化完成：cn={}, algorithm={}, serial={}", companyUscc, row.getSubjectCn(), algorithm, serial);
        return row;
    }

    /**
     * 数据库记录缺失但落盘文件存在时，从文件重建记录
     */
    private CaCert doRebuildFromFiles(String companyUscc) {
        try {
            String certPem = Files.readString(rootCertFile(companyUscc), StandardCharsets.UTF_8);
            X509Certificate cert = PemUtil.parseCert(certPem);
            // 算法探测沿用存量口径（按配置判断 SM2/RSA）；公司域以注册表 algorithm 为准
            String hint = CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                    ? props.getRootCa().getAlgorithm() : requireCompany(companyUscc).getAlgorithm();
            CaCert row = new CaCert();
            row.setCompanyUscc(companyUscc);
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
            row.setCreatedAt(LocalDateTime.now());
            certMapper.insert(row);
            return row;
        } catch (IOException e) {
            throw new IllegalStateException("根CA文件恢复失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询根CA记录（兼容入口=default 公司）
     */
    public CaCert findRoot() {
        return findRoot(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 查询指定公司根CA记录（serial 全局唯一，公司维度过滤取最新）
     */
    public CaCert findRoot(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        return doFindRoot(companyUscc);
    }

    private CaCert doFindRoot(String companyUscc) {
        List<CaCert> roots = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_ROOT)
                .eq(CaCert::getCompanyUscc, companyUscc)
                .orderByDesc(CaCert::getCreatedAt));
        return roots.isEmpty() ? null : roots.get(0);
    }

    /**
     * 加载根证书（兼容入口=default 公司）
     */
    public X509Certificate loadRootCert() {
        return loadRootCert(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 加载指定公司根证书
     */
    public X509Certificate loadRootCert(String companyUscc) {
        CaCert root = findRoot(companyUscc);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /**
     * 加载根CA私钥（兼容入口=default 公司）
     */
    public PrivateKey loadRootKey() {
        return loadRootKey(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 加载公司根证书（内部版，假定调用方已持有公司锁或仅需只读一致性）
     */
    private X509Certificate doLoadRootCert(String companyUscc) {
        CaCert root = doFindRoot(companyUscc);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /**
     * 加载公司根CA私钥：优先门限解封缓存；否则 SM4 加密文件（旧模式）；份额分持态必须显式解封。
     * 与 seal/unseal/份额迁移同为公司锁方法：读缓存与封存写入互斥，杜绝"读到刚被封存的私钥"。
     */
    public PrivateKey loadRootKey(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            return doLoadRootKey(companyUscc, ov, st);
        }
    }

    private PrivateKey doLoadRootKey(String companyUscc, KeyVault ov, CompanyCaState st) {
        if (st.rootKeyCache != null) {
            return st.rootKeyCache;
        }
        CaCert root = doFindRoot(companyUscc);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        String keyPem = loadRootKeyFile(companyUscc);
        if (keyPem == null) {
            if (doSharesExist(companyUscc)) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请由密钥管理员提交任意 3 份份额解封后再轮换 SUB_CA");
            }
            throw new IllegalStateException("根CA私钥缺失，请恢复密钥或重建根CA");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    /**
     * 根私钥状态（兼容入口=default 公司）
     */
    public String rootKeyState() {
        return rootKeyState(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司根私钥状态：SHARES（门限分持）/ ONLINE / SEALED / MISSING
     */
    public String rootKeyState(String companyUscc) {
        return doRootKeyState(companyUscc, vaultFor(companyUscc));
    }

    private String doRootKeyState(String companyUscc, KeyVault ov) {
        if (doSharesExist(companyUscc)) {
            return ROOT_KEY_SHARES;
        }
        if (rootKeyFileSealed(companyUscc)) {
            return ROOT_KEY_SEALED;
        }
        return rootKeyFileExists(companyUscc) ? ROOT_KEY_ONLINE : ROOT_KEY_MISSING;
    }

    /**
     * 封存根私钥（兼容入口=default 公司）
     */
    public void sealRootKey() {
        sealRootKey(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 封存公司根私钥：门限分持态清除内存缓存；旧模式将加密文件移存 .sealed（幂等）
     */
    public void sealRootKey(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            CaCert root = doFindRoot(companyUscc);
            if (root == null) {
                throw new IllegalStateException("根CA未初始化，无法封存");
            }
            doEnsureSubCa(companyUscc, ov, st);
            st.rootKeyCache = null;
            if (rootKeyFileExists(companyUscc)) {
                sealRootKeyFile(companyUscc);
            }
            log.info("[根CA][{}] 根私钥已封存（serial={}，门限分持态={}），日常签发由 SUB_CA 承担",
                    companyUscc, root.getSerialHex(), doSharesExist(companyUscc));
        }
    }

    /**
     * 解封根私钥（兼容入口=default 公司）
     */
    public void unsealRootKey() {
        unsealRootKey(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 解封公司根私钥（旧模式移名恢复在线态；门限分持态须走份额解封接口）
     */
    public void unsealRootKey(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            if (doFindRoot(companyUscc) == null) {
                throw new IllegalStateException("根CA未初始化，无法解封");
            }
            if (doSharesExist(companyUscc)) {
                throw new IllegalStateException("根私钥已切换为 Shamir(3,5) 门限分持，请用份额解封接口提交任意 3 份");
            }
            unsealRootKeyFile(companyUscc);
            if (rootKeyFileSealed(companyUscc)) {
                throw new IllegalStateException("根私钥文件缺失（已封存），无法解封");
            }
            log.info("[根CA][{}] 根私钥已解封，恢复在线态", companyUscc);
        }
    }

    /**
     * 读取单份门限份额 JSON 文本（兼容入口=default 公司）
     */
    public String readShareJson(int index) {
        return readShareJson(CaCompany.PRIMARY_COMPANY_USCC, index);
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /**
     * 读取公司单份门限份额 JSON 文本（管理端导出用；不存在抛 IllegalStateException）
     */
    public String readShareJson(String companyUscc, int index) {
        Path file = shareFile(companyUscc, index);
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
     * 5 份份额是否全部生成（兼容入口=default 公司）
     */
    public boolean sharesExist() {
        return sharesExist(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司 5 份份额是否全部生成（任一缺失视为未完成，避免以不完整份额误判）
     */
    public boolean sharesExist(String companyUscc) {
        return doSharesExist(companyUscc);
    }

    private boolean doSharesExist(String companyUscc) {
        if (!Files.isDirectory(sharesDir(companyUscc))) {
            return false;
        }
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            if (!Files.exists(shareFile(companyUscc, i))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 门限份额保管人绑定（建根/开公司时指定，一人一份落表） ====================

    /**
     * 指定/重指定根私钥份额保管人：把已生成的 5 份磁盘份额各绑定一名保管人，落表 {@code ssl_company_key_share}。
     * 幂等：重复指定会清空旧绑定后重绑。用于开公司时自动指派，或存量/主公司后续手动指派。
     *
     * @param companyUscc     公司标识
     * @param holderUsernames 5 名保管人登录账号（须存在且启用，互不相同）
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRootShares(String companyUscc, List<String> holderUsernames) {
        if (holderUsernames == null || holderUsernames.size() != SHARE_TOTAL) {
            throw new IllegalArgumentException("须指定恰好 " + SHARE_TOTAL + " 名密钥份额保管人");
        }
        Set<String> distinct = new java.util.LinkedHashSet<>();
        for (String u : holderUsernames) {
            if (u == null || u.isBlank()) {
                throw new IllegalArgumentException("保管人账号不可为空");
            }
            if (!distinct.add(u.trim())) {
                throw new IllegalArgumentException("保管人账号不可重复：" + u.trim());
            }
        }
        // 确保 5 份份额已落盘（initRoot 通常已生成；存量环境此处补齐）
        ensureRootShares(companyUscc);
        // 校验保管人均为启用状态账号，并取真实姓名
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getUsername, distinct));
        if (users.size() != distinct.size()) {
            throw new IllegalArgumentException("指定的保管人中存在不存在的账号，请核对");
        }
        for (SysUser u : users) {
            if (u.getStatus() != null && u.getStatus() != 1) {
                throw new IllegalArgumentException("保管人「" + u.getUsername() + "」未启用，不能担任密钥保管人");
            }
            // 分权制衡：根私钥份额保管人必须为本平台"密钥管理员(KEY_ADMIN)"角色
            // （其职责定义即"根密钥门限分持/封存/解封与份额保管"），与厂商 key-ceremony "具名保管人"对齐，
            // 且天然排除系统管理员(ADMIN)等冲突角色，避免一人既掌平台又掌根私钥。
            if (!RoleCodeEnum.KEY_ADMIN.name().equals(u.getRoleCode())) {
                throw new IllegalArgumentException("保管人「" + u.getUsername() + "」角色须为密钥管理员(KEY_ADMIN)，当前为「" + u.getRoleCode() + "」");
            }
            // 单租户化后账号不再携带公司域，保管人归属由本公司 key_share 表绑定决定
        }
        // 清空旧绑定后重绑（幂等）
        keyShareMapper.delete(new LambdaQueryWrapper<CompanyKeyShare>()
                .eq(CompanyKeyShare::getCompanyUscc, companyUscc));
        List<CompanyKeyShare> rows = new ArrayList<>();
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            final String holderName = holderUsernames.get(i - 1).trim();
            String json = readShareJson(companyUscc, i);
            SysUser u = users.stream().filter(x -> x.getUsername().equals(holderName)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("保管人账号不匹配：" + holderName));
            CompanyKeyShare row = new CompanyKeyShare();
            row.setCompanyUscc(companyUscc);
            row.setShareIndex(i);
            row.setHolderUserId(u.getId());
            row.setHolderRealName(u.getRealName());
            row.setShareJson(json);
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        // 批量插入（MP saveBatch 或逐条；此处逐条保证清晰）
        for (CompanyKeyShare r : rows) {
            keyShareMapper.insert(r);
        }
        log.info("[根CA][{}] 已指定 {} 名密钥份额保管人（一人一份，Shamir {}/{}）", companyUscc, SHARE_TOTAL, SHARE_THRESHOLD, SHARE_TOTAL);
    }

    /**
     * 查询某公司全部份额保管人（不含份额明文，供审批人名单/展示用）
     */
    public List<CompanyKeyShare> listKeyShareHolders(String companyUscc) {
        return keyShareMapper.selectList(new LambdaQueryWrapper<CompanyKeyShare>()
                .eq(CompanyKeyShare::getCompanyUscc, companyUscc)
                .orderByAsc(CompanyKeyShare::getShareIndex));
    }

    /**
     * 查询当前登录人是否为某公司份额保管人，并返回其自身份额（仅本人可见）；
     * 非保管人返回 null（调用方据此屏蔽）。
     */
    public CompanyKeyShare readShareForHolder(String companyUscc, Long userId) {
        if (userId == null) {
            return null;
        }
        return keyShareMapper.selectOne(new LambdaQueryWrapper<CompanyKeyShare>()
                .eq(CompanyKeyShare::getCompanyUscc, companyUscc)
                .eq(CompanyKeyShare::getHolderUserId, userId));
    }

    /**
     * 份额状态（兼容入口=default 公司）
     */
    public RootSharesVO shareState() {
        return shareState(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司份额状态（管理端展示：5 份各自存在性 + 阈值）
     */
    public RootSharesVO shareState(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        List<RootSharesVO.ShareItem> shares = new ArrayList<>();
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            RootSharesVO.ShareItem item = new RootSharesVO.ShareItem();
            item.setIndex(i);
            item.setExists(Files.exists(shareFile(companyUscc, i)));
            shares.add(item);
        }
        RootSharesVO vo = new RootSharesVO();
        vo.setGenerated(doSharesExist(companyUscc));
        vo.setTotal(SHARE_TOTAL);
        vo.setThreshold(SHARE_THRESHOLD);
        vo.setShares(shares);
        return vo;
    }

    /**
     * 根私钥门限化（兼容入口=default 公司）
     */
    public void ensureRootShares() {
        ensureRootShares(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司根私钥门限化（幂等）：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 仅当 5 份份额全部成功落盘后才删除原始文件；任何一份失败则保留原始文件并报错（不静默降级）。
     * 触发：run()/initRoot() 以「份额文件是否齐全」为唯一判据调用（中断/重启后可安全重试）；旧环境也可由管理员显式调用迁移接口。
     */
    public void ensureRootShares(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            doEnsureRootShares(companyUscc, ov, st);
        }
    }

    private void doEnsureRootShares(String companyUscc, KeyVault ov, CompanyCaState st) {
        if (doSharesExist(companyUscc)) {
            return;
        }
        CaCert root = doFindRoot(companyUscc);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化，无法生成门限份额");
        }
        String keyPem = loadRootKeyFile(companyUscc);
        if (keyPem == null) {
            throw new IllegalStateException("根私钥不可用（无加密副本可拆分），无法生成门限份额");
        }
        try {
            Files.createDirectories(sharesDir(companyUscc));
            PrivateKey key = PemUtil.parsePrivateKey(keyPem);
            ShamirUtil.Share[] shares = ShamirUtil.split(key.getEncoded(), SHARE_TOTAL, SHARE_THRESHOLD);
            for (ShamirUtil.Share s : shares) {
                Files.writeString(shareFile(companyUscc, s.index()),
                        objectMapper.writeValueAsString(s), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("门限份额生成失败（原始根私钥未删除）: " + e.getMessage(), e);
        }
        // 5 份份额全部落盘成功后才删除单点加密文件（根私钥刻意不落库，仅清文件形态）
        if (doSharesExist(companyUscc)) {
            deleteRootKeyFile(companyUscc);
            st.rootKeyCache = null;
            log.warn("[根CA][{}] 根私钥已切换为 Shamir(3,5) 门限分持（serial={}）：5 份份额落盘，原始加密文件已删除，恢复需任意 3 份",
                    companyUscc, root.getSerialHex());
        } else {
            throw new IllegalStateException("份额写入不完整，已保留原始根私钥，请检查份额目录后重试");
        }
    }

    /**
     * 旧环境迁移（兼容入口=default 公司）
     */
    public RootSharesVO migrateRootShares() {
        return migrateRootShares(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司迁移：把已存在的 SM4 加密根私钥转为门限份额（显式调用，避免误删生产密钥）
     */
    public RootSharesVO migrateRootShares(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            if (doSharesExist(companyUscc)) {
                return shareState(companyUscc);
            }
            doEnsureRootShares(companyUscc, ov, st);
            return shareState(companyUscc);
        }
    }

    /**
     * 份额解封（兼容入口=default 公司）
     */
    public void unsealRootKeyWithShares(List<String> shareJsons) {
        unsealRootKeyWithShares(CaCompany.PRIMARY_COMPANY_USCC, shareJsons);
    }

    /**
     * 公司份额解封：提交任意 3 份门限份额，重组根私钥并置入该公司内存缓存（仅本次进程有效）。
     * 重组结果用该公司根证书公钥做签名挑战校验，防止份额被篡改/错配。
     */
    public void unsealRootKeyWithShares(String companyUscc, List<String> shareJsons) {
        CompanyCaState st = stateOf(companyUscc);
        synchronized (st.lock) {
            CaCert root = doFindRoot(companyUscc);
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
                log.warn("[根CA][{}] 已通过 {} 份门限份额解封根私钥（内存缓存），使用后请及时封存", companyUscc, shares.size());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("份额解封失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 用根证书公钥校验重组私钥匹配（签名挑战，防份额错配/篡改；份额天然按公司目录存放，跨公司份额无法互用）
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
     * 管理端主动初始化（兼容入口=default 公司）
     */
    public CaStatusVO initRoot() {
        return initRoot(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司根CA初始化：已就绪则原样返回；根私钥丢失则吊销旧根并重建（连带吊销旧SUB_CA）。
     * default 域保持存量 CN/算法配置；新公司按注册表 algorithm/subject_o 生成独立根。
     */
    public CaStatusVO initRoot(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            migrateRootKeyFromDbIfNeeded(companyUscc, ov);
            CaCert root = doFindRoot(companyUscc);
            if (root != null && (rootKeyFileExists(companyUscc) || doSharesExist(companyUscc))) {
                // 幂等修复路径：首建中途失败会留下"有根无SUB_CA"的裸域，此处补建（已有有效SUB_CA即直接复用）
                doEnsureSubCa(companyUscc, ov, st);
                return doStatus(companyUscc, ov);
            }
            if (root != null) {
                doRevokeAllSubCa(companyUscc, ov, "ROOT_KEY_LOST", root.getSubjectCn());
                root.setStatus("REVOKED");
                root.setRevokeReason("ROOT_KEY_LOST");
                root.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(root);
                // 清理旧根密钥残留（含半套旧份额）：防遗留份额文件被"份额齐全"判据误命中、跳过新根门限化
                doClearRootKeyMaterial(companyUscc, ov);
                ov.deleteDbEntry("root", "key");
                log.warn("[根CA][{}] 旧根私钥缺失，已吊销（serial={}）并重建新根", companyUscc, root.getSerialHex());
            } else {
                // 全新生成根CA，但盘上可能残留"上一代根"的份额文件（典型场景：清库重建——库里根证书记录已删、
                // 磁盘 data/certs 未清）。必须先清掉，否则"5 份份额齐全"判据会误命中旧份额 →
                // doEnsureRootShares 直接跳过 → 新根私钥不门限化、且那 5 份份额仍属旧根私钥 →
                // 后续贴 3 份解封时签名挑战必然失败（份额重组密钥与根证书不匹配）。
                doClearShareFiles(companyUscc);
            }
            CaCert created = doGenerateRoot(companyUscc, ov);
            doEnsureSubCa(companyUscc, ov, st);
            // 新根自动切换为 Shamir(3,5) 门限分持（删单点加密文件）
            doEnsureRootShares(companyUscc, ov, st);
            CaStatusVO vo = doStatus(companyUscc, ov);
            vo.setMessage("根CA已重建：" + created.getSubjectCn() + "（序列号 " + created.getSerialHex() + "），已签发新SUB_CA，根私钥已切换为 Shamir(3,5) 门限分持（请尽快导存 5 份份额）");
            return vo;
        }
    }

    /**
     * 吊销该公司全部有效 SUB_CA（根重建/根丢失时调用，旧链整体失效），并清理其磁盘私钥（仅 CA 类密钥）
     */
    private void doRevokeAllSubCa(String companyUscc, KeyVault ov, String reason, String rootCn) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getCompanyUscc, companyUscc));
        for (CaCert sub : subs) {
            if (!"REVOKED".equals(sub.getStatus())) {
                sub.setStatus("REVOKED");
                sub.setRevokeReason(reason);
                sub.setRevokedAt(LocalDateTime.now());
                certMapper.updateById(sub);
                purgeCaKeyFiles(subCaDir(companyUscc), ov, sub.getSerialHex());
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
     * 根重建前清理该公司旧根密钥残留：全形态加密文件 + 旧门限份额文件
     */
    private void doClearRootKeyMaterial(String companyUscc, KeyVault ov) {
        purgeCaKeyFiles(rootDir(companyUscc), ov, "ROOT");
        doClearShareFiles(companyUscc);
    }

    /**
     * 删除该公司磁盘上残留的门限份额文件（shares-1..5.json）。
     *
     * <p>独立成方法的原因：{@link #doEnsureRootShares} 以"5 份份额文件是否齐全"作为幂等判据，
     * 而该判据无法区分"当前根的份额"与"上一代根遗留的份额"。清库重建（库里根证书记录已删、
     * 磁盘 store-dir 未清）时若不预清理，旧份额会被误判为齐全 → 新根跳过门限化，
     * 保管人拿到的仍是旧根私钥的份额，后续解封签名挑战必然失败。</p>
     */
    private void doClearShareFiles(String companyUscc) {
        for (int i = 1; i <= SHARE_TOTAL; i++) {
            Path stale = shareFile(companyUscc, i);
            try {
                if (Files.deleteIfExists(stale)) {
                    log.info("[根CA][{}] 已删除残留门限份额文件: {}", companyUscc, stale);
                }
            } catch (IOException e) {
                log.warn("[根CA][{}] 残留份额文件删除失败（不阻断重建）: {}", companyUscc, e.getMessage());
            }
        }
    }

    /**
     * 确保 SUB_CA 存在（兼容入口=default 公司）
     */
    public CaCert ensureSubCa() {
        return ensureSubCa(CaCompany.PRIMARY_COMPANY_USCC);
    }

    // ==================== SUB_CA（服务签发二级CA） ====================

    /**
     * 确保公司 SUB_CA 存在（幂等，新建/轮换专用）：仅限启动、封存窗口（sealRootKey/initRoot）
     * 与每日预检 {@link #dailyPrecheck()} 调用；日常签发路径不再经过这里。
     */
    public CaCert ensureSubCa(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            return doEnsureSubCa(companyUscc, ov, st);
        }
    }

    /**
     * 强制重建二级CA（高敏操作，须根私钥已通过份额解封置入内存缓存）：
     * 清除旧 SUB_CA 私钥使 {@link #doEnsureSubCa} 走重建分支（旧有效 SUB_CA 标记 EXPIRED），用根重新签发新 SUB_CA。
     * 调用前必须由 {@link #unsealRootKeyWithShares} 完成门槛解封（{@code rootKeyCache != null}）。
     */
    public CaCert rebuildSubCa(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            if (st.rootKeyCache == null) {
                throw new IllegalStateException("根CA私钥未解封（内存无缓存），请先通过 ≥" + SHARE_THRESHOLD
                        + " 份份额解封根私钥后再重建二级CA");
            }
            // 清除旧 SUB_CA 私钥，使 doEnsureSubCa 走重建分支（旧有效 SUB_CA 标记 EXPIRED）
            ov.deleteDbEntry("subca", "key");
            return doEnsureSubCa(companyUscc, ov, st);
        }
    }

    private CaCert doEnsureSubCa(String companyUscc, KeyVault ov, CompanyCaState st) {
        CaCert sub = doFindValidSubCa(companyUscc);
        if (sub != null && ov.keyExists("subca", "key")) {
            return sub;
        }
        // 根私钥离线（封存/门限分持未解封）时不允许新建/轮换 SUB_CA
        String rkState = doRootKeyState(companyUscc, ov);
        if (sub == null && (ROOT_KEY_SEALED.equals(rkState)
                || (ROOT_KEY_SHARES.equals(rkState) && st.rootKeyCache == null))) {
            throw new IllegalStateException("根CA私钥离线（封存/门限分持）且无有效 SUB_CA，请先解封根密钥后轮换签发");
        }
        X509Certificate rootCert = doLoadRootCert(companyUscc);
        PrivateKey rootKey = doLoadRootKey(companyUscc, ov, st);

        CssslProperties.SubCa subProps = props.getSubCa();
        // SUB_CA 算法跟随该公司根（RSA根→RSA SUB_CA，国密根→SM2 SUB_CA）
        String algorithm = doFindRoot(companyUscc).getAlgorithm();
        KeyPair subKey = CertUtil.generateKeyPair(algorithm);
        X509Certificate subCert;
        if (CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            // default 域走旧 CN 入口（DN 含 O=CSSSL），存量证书逐字节兼容
            subCert = CertUtil.issue(subKey, rootCert, rootKey,
                    subProps.getCn(), TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);
        } else {
            CaCompany company = requireCompany(companyUscc);
            subCert = CertUtil.issue(CertUtil.subjectDn("CSSSL SubCA - " + company.getCompanyName(), company.getSubjectO()),
                    subKey.getPublic(), rootCert, rootKey,
                    TYPE_SUB_CA, subProps.getValidityDays(), null, null, null);
        }

        String serial = subCert.getSerialNumber().toString(16);
        try {
            Files.createDirectories(subCaDir(companyUscc));
            Files.writeString(subCaCertFile(companyUscc), PemUtil.certToPem(subCert), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("SUB_CA落盘失败: " + e.getMessage(), e);
        }
        ov.storeKey("subca", "key", PemUtil.keyToPem(subKey.getPrivate()));

        // 该公司旧 SUB_CA 标记 EXPIRED（由新 SUB_CA 接管在线签发）
        List<CaCert> oldSubs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getCompanyUscc, companyUscc)
                .eq(CaCert::getStatus, "VALID"));
        for (CaCert old : oldSubs) {
            if (!old.getSerialHex().equalsIgnoreCase(serial)) {
                old.setStatus("EXPIRED");
                certMapper.updateById(old);
            }
        }

        CaCert row = new CaCert();
        row.setCompanyUscc(companyUscc);
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
        row.setCreatedAt(LocalDateTime.now());
        certMapper.insert(row);
        log.info("[SUB_CA][{}] 已用根签发二级CA：cn={}, algorithm={}, serial={}, 有效期{}天",
                companyUscc, row.getSubjectCn(), algorithm, serial, subProps.getValidityDays());
        return row;
    }

    /**
     * 查询当前有效 SUB_CA（兼容入口=default 公司）
     */
    public CaCert findValidSubCa() {
        return findValidSubCa(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 查询公司当前有效 SUB_CA（未过期且距到期 > renewBeforeDays）
     */
    public CaCert findValidSubCa(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        return doFindValidSubCa(companyUscc);
    }

    private CaCert doFindValidSubCa(String companyUscc) {
        List<CaCert> subs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getCompanyUscc, companyUscc)
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
     * 每日到期预检（定时任务，与签发路径解耦）：遍历 default + 全部 ACTIVE 公司逐一预检；
     * 存量单根环境（仅有 default）行为与路线 B 完全一致。
     */
    @Scheduled(cron = "${csssl.subca-precheck-cron:0 30 3 * * ?}")
    public void dailyPrecheck() {
        List<String> keys = new ArrayList<>();
        keys.add(CaCompany.PRIMARY_COMPANY_USCC);
        try {
            List<CaCompany> companies = companyMapper.selectList(new LambdaQueryWrapper<CaCompany>()
                    .eq(CaCompany::getStatus, CompanyStatusEnum.ACTIVE.getCode())
                    .ne(CaCompany::getUscc, CaCompany.PRIMARY_COMPANY_USCC));
            for (CaCompany company : companies) {
                keys.add(company.getUscc());
            }
        } catch (Exception e) {
            log.warn("[SUB_CA] 公司清单读取失败，本轮仅预检 default 域: {}", e.getMessage());
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
     * 公司 SUB_CA 到期预检：缺失/进入 renewBeforeDays 续期窗口且根私钥可用（在线或份额已解封）时轮换；
     * 根封存期间只告警，绝不隐式触发需根私钥的操作。
     */
    public void dailyPrecheck(String companyUscc) {
        CompanyCaState st = stateOf(companyUscc);
        KeyVault ov = vaultFor(companyUscc);
        synchronized (st.lock) {
            try {
                if (doFindValidSubCa(companyUscc) != null && ov.keyExists("subca", "key")) {
                    return;
                }
                String rkState = doRootKeyState(companyUscc, ov);
                boolean rootUsable = ROOT_KEY_ONLINE.equals(rkState)
                        || (ROOT_KEY_SHARES.equals(rkState) && st.rootKeyCache != null);
                if (!rootUsable) {
                    log.warn("[SUB_CA][{}] 预检发现 SUB_CA 缺失/临近到期，但根私钥离线（{}），请在根解封窗口轮换", companyUscc, rkState);
                    return;
                }
                doEnsureSubCa(companyUscc, ov, st);
                log.info("[SUB_CA][{}] 到期预检完成轮换", companyUscc);
            } catch (Exception e) {
                log.warn("[SUB_CA][{}] 到期预检失败: {}", companyUscc, e.getMessage());
            }
        }
    }

    /**
     * 当前签发CA（兼容入口=default 公司）
     */
    public X509Certificate signingCert() {
        return signingCert(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司当前签发CA（SUB_CA）证书：只取现有有效 SUB_CA，不触发轮换（轮换见 {@link #dailyPrecheck(String)}）
     */
    public X509Certificate signingCert(String companyUscc) {
        return PemUtil.parseCert(doRequireValidSubCa(companyUscc).getCertPem());
    }

    public PrivateKey signingKey() {
        return signingKey(CaCompany.PRIMARY_COMPANY_USCC);
    }

    public PrivateKey signingKey(String companyUscc) {
        doRequireValidSubCa(companyUscc);
        String keyPem = vaultFor(companyUscc).loadKey("subca", "key");
        if (keyPem == null) {
            throw new IllegalStateException("SUB_CA私钥缺失，请在根解封窗口轮换");
        }
        return PemUtil.parsePrivateKey(keyPem);
    }

    private CaCert doRequireValidSubCa(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        CaCert sub = doFindValidSubCa(companyUscc);
        if (sub == null) {
            // 区分两种成因便于运维定位：域内从未建成 SUB_CA（裸根）vs 建成但已过期/被吊销
            throw new IllegalStateException(doCurrentSubCa(companyUscc) == null
                    ? "SUB_CA缺失（公司域未初始化完成），请重新调用公司初始化补建"
                    : "SUB_CA已过期，请在根解封窗口轮换");
        }
        return sub;
    }

    /**
     * 当前有效 SUB_CA 记录（兼容入口=default 公司）
     */
    public CaCert currentSubCa() {
        return currentSubCa(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司当前有效 SUB_CA 记录（供链组装/OCSP 使用；无则返回该公司最近一张；均无则 null）
     */
    public CaCert currentSubCa(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        return doCurrentSubCa(companyUscc);
    }

    private CaCert doCurrentSubCa(String companyUscc) {
        CaCert sub = doFindValidSubCa(companyUscc);
        return sub != null ? sub : certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, TYPE_SUB_CA)
                .eq(CaCert::getCompanyUscc, companyUscc)
                .orderByDesc(CaCert::getCreatedAt)
                .last("limit 1"));
    }

    /**
     * 根CA状态（兼容入口=default 公司）
     */
    public CaStatusVO status() {
        return status(CaCompany.PRIMARY_COMPANY_USCC);
    }

    // ==================== 状态/导出/签发密钥存取 ====================

    /**
     * 公司根CA状态（不抛异常，返回未初始化原因供页面展示；含 SUB_CA 与根密钥封存状态）
     */
    public CaStatusVO status(String companyUscc) {
        return doStatus(companyUscc, vaultFor(companyUscc));
    }

    private CaStatusVO doStatus(String companyUscc, KeyVault ov) {
        CaStatusVO vo = new CaStatusVO();
        CaCert root = doFindRoot(companyUscc);
        if (root == null) {
            vo.setInitialized(false);
            if (Files.exists(rootCertFile(companyUscc)) || rootKeyFileExists(companyUscc)) {
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
        vo.setRootKeyState(doRootKeyState(companyUscc, ov));
        vo.setShareState(shareState(companyUscc));
        if (ROOT_KEY_MISSING.equals(vo.getRootKeyState())) {
            vo.setInitialized(false);
            vo.setMessage("根CA私钥缺失，无法签发二级CA，请恢复密钥或重建根CA");
        } else {
            vo.setInitialized(true);
        }
        CaCert sub = doCurrentSubCa(companyUscc);
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
     * 根证书 PEM（兼容入口=default 公司）
     */
    public String exportRootPem() {
        return exportRootPem(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司根证书 PEM（业务系统信任链配置用）
     */
    public String exportRootPem(String companyUscc) {
        CaCert root = findRoot(companyUscc);
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return root.getCertPem();
    }

    /**
     * 当前 SUB_CA 证书 PEM（兼容入口=default 公司）
     */
    public String exportSubCaPem() {
        return exportSubCaPem(CaCompany.PRIMARY_COMPANY_USCC);
    }

    /**
     * 公司当前 SUB_CA 证书 PEM（中间CA链配置用）
     */
    public String exportSubCaPem(String companyUscc) {
        CaCert sub = currentSubCa(companyUscc);
        if (sub == null) {
            throw new IllegalStateException("SUB_CA未签发");
        }
        return sub.getCertPem();
    }

    /**
     * 签发证书私钥加密落盘（兼容入口=default 公司）
     */
    public void storeIssuedKey(String serialHex, String keyPem) {
        storeIssuedKey(CaCompany.PRIMARY_COMPANY_USCC, serialHex, keyPem);
    }

    /**
     * 公司签发证书私钥加密落盘
     */
    public void storeIssuedKey(String companyUscc, String serialHex, String keyPem) {
        vaultFor(companyUscc).storeKey("issued/" + serialHex, "key", keyPem);
    }

    /**
     * 读取签发证书私钥（兼容入口=default 公司）
     */
    public String loadIssuedKey(String serialHex) {
        return loadIssuedKey(CaCompany.PRIMARY_COMPANY_USCC, serialHex);
    }

    /**
     * 读取公司签发证书私钥（无则返回 null；已封存/主密钥错误时抛异常）
     */
    public String loadIssuedKey(String companyUscc, String serialHex) {
        return vaultFor(companyUscc).loadKey("issued/" + serialHex, "key");
    }

    public boolean issuedKeyExists(String serialHex) {
        return issuedKeyExists(CaCompany.PRIMARY_COMPANY_USCC, serialHex);
    }

    public boolean issuedKeyExists(String companyUscc, String serialHex) {
        return vaultFor(companyUscc).keyExists("issued/" + serialHex, "key");
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

    private static final class CompanyCaState {
        /**
         * 公司锁对象：该公司 rootKeyCache 及根/子CA密钥读写的唯一互斥域
         */
        final Object lock = new Object();
        /**
         * 份额解封后的临时根私钥（仅内存，用后 seal 清除）。
         * 所有读写（loadRootKey / seal / unseal / 份额迁移 / ensureSubCa 离线判定）统一持本公司锁。
         */
        PrivateKey rootKeyCache;
    }
}
