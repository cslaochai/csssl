package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.dto.CaStatusVo;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
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
 * 根CA管理：首次启动自动生成根CA（RSA2048 / SM2 国密），自签根证书并落盘。
 * 私钥只落盘（store-dir/root/key.pem），数据库只存公钥证书与元信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaService implements ApplicationRunner {

    public static final String TYPE_ROOT = "ROOT";
    private static final String CN_ISSUER = "CSSSL";

    private final CssslProperties props;
    private final CaCertMapper certMapper;

    private Path rootDir() {
        return Path.of(props.getStoreDir(), "root");
    }

    private Path rootCertFile() {
        return rootDir().resolve("cert.pem");
    }

    private Path rootKeyFile() {
        return rootDir().resolve("key.pem");
    }

    /** 签发证书私钥/证书落盘目录 */
    public Path issuedDir(String serialHex) {
        return Path.of(props.getStoreDir(), "issued", serialHex);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureRoot();
        } catch (Exception e) {
            log.error("[根CA] 启动初始化失败，请检查 store-dir 权限与数据库连接: {}", e.getMessage());
        }
    }

    /**
     * 确保根CA存在（幂等）：优先恢复文件态 → 其次重建数据库记录 → 最后全新生成。
     */
    public synchronized CaCert ensureRoot() {
        CaCert root = findRoot();

        if (root != null && Files.exists(rootKeyFile())) {
            markExpiredIfNeeded(root);
            return root;
        }
        if (root != null && !Files.exists(rootKeyFile())) {
            // 私钥文件缺失：签发能力已失效，抛出明确错误，由管理端决定重建
            throw new IllegalStateException("根CA私钥文件缺失（" + rootKeyFile() + "），无法签发证书，请恢复密钥或重建根CA");
        }
        if (root == null && Files.exists(rootCertFile()) && Files.exists(rootKeyFile())) {
            // 数据库记录丢失但密钥文件在：从文件重建记录
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
            Files.writeString(rootKeyFile(), PemUtil.keyToPem(keyPair.getPrivate()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("根CA落盘失败: " + e.getMessage(), e);
        }

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

    /** 数据库记录缺失但落盘文件存在时，从文件重建记录（证书内容与序列号取自证书本身） */
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

    /** 加载根证书（X509Certificate） */
    public X509Certificate loadRootCert() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        return PemUtil.parseCert(root.getCertPem());
    }

    /** 加载根CA私钥（只从落盘文件读取） */
    public PrivateKey loadRootKey() {
        CaCert root = findRoot();
        if (root == null) {
            throw new IllegalStateException("根CA未初始化");
        }
        try {
            String keyPem = Files.readString(rootKeyFile(), StandardCharsets.UTF_8);
            return PemUtil.parsePrivateKey(keyPem);
        } catch (IOException e) {
            throw new IllegalStateException("根CA私钥读取失败: " + e.getMessage(), e);
        }
    }

    /** 管理端主动初始化：已初始化则原样返回；私钥丢失则吊销旧根并重建（审计留痕） */
    public synchronized CaStatusVo initRoot() {
        CaCert root = findRoot();
        if (root != null && Files.exists(rootKeyFile())) {
            return status();
        }
        if (root != null) {
            root.setStatus("REVOKED");
            root.setRevokeReason("ROOT_KEY_LOST");
            root.setRevokedAt(LocalDateTime.now());
            certMapper.updateById(root);
            log.warn("[根CA] 旧根私钥缺失，已吊销（serial={}）并重建新根", root.getSerialHex());
        }
        CaCert created = generateRoot();
        CaStatusVo vo = status();
        vo.setMessage("根CA已重建：" + created.getSubjectCn() + "（序列号 " + created.getSerialHex() + "）");
        return vo;
    }

    /** 根CA状态（不抛异常，返回未初始化原因供页面展示） */
    public CaStatusVo status() {
        CaStatusVo vo = new CaStatusVo();
        CaCert root = findRoot();
        if (root == null) {
            vo.setInitialized(false);
            if (Files.exists(rootKeyFile())) {
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
        if (!Files.exists(rootKeyFile())) {
            vo.setInitialized(false);
            vo.setMessage("根CA私钥文件缺失，无法签发证书，请恢复密钥或重建根CA");
        } else {
            vo.setInitialized(true);
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

    /** 证书过期后自动标记 EXPIRED（仅状态展示，不参与吊销） */
    private void markExpiredIfNeeded(CaCert cert) {
        if ("VALID".equals(cert.getStatus()) && cert.getValidTo() != null
                && cert.getValidTo().isBefore(LocalDateTime.now())) {
            cert.setStatus("EXPIRED");
            certMapper.updateById(cert);
        }
    }
}
