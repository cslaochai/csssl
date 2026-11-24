package com.csssl.config;

import com.csssl.mapper.KeyVaultEntryMapper;
import com.csssl.util.KeyVault;
import cn.hutool.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 密钥保险库配置：声明 default 公司域（存量单根/平台主公司）的 {@link KeyVault} 单例 bean，
 * 由 CaService 与 TsaService 共用（消除各自 new KeyVault 的重复 SM4 初始化与 master.key 首启竞态）。
 *
 * <p>每公司独立的保险库解析（含目录/主密钥/缓存）已下沉到 {@link CompanyVaultResolver}；
 * 统一社会信用代码校验已下沉到 {@link com.csssl.util.UsccValidator}。本配置类只负责 bean 装配。</p>
 */
@Slf4j
@Configuration
public class KeyVaultConfig {

    /**
     * 主密钥解析：优先配置/环境变量 SSL_MASTER_KEY，否则首启生成随机密钥落盘 store-dir/master.key
     */
    private static String resolveMasterKey(CssslProperties props) {
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

    @Bean
    public KeyVault keyVault(CssslProperties props, KeyVaultEntryMapper mapper) {
        // storeDir 必须是显式绝对路径（dev 写死 source/ssl_backend/data/certs，容器写 /app/data/certs）：
        // 不再依赖启动目录或 classpath 推断，避免换 IDE / 换 cwd / 拆模块后落第二套盘、换 KEK。
        Path resolvedStoreDir = resolveStoreDir(props.getStoreDir());
        props.setStoreDir(resolvedStoreDir.toString());
        String masterKey = resolveMasterKey(props);
        log.info("[密钥保险库] default 域 store-dir={} KEK 指纹={}", resolvedStoreDir, kekFingerprint(masterKey));
        return new KeyVault(null, resolvedStoreDir, masterKey, mapper);
    }

    /**
     * 校验并规范化 storeDir：必须是显式绝对路径。
     *
     * <p>这里刻意不再做「从 classpath 向上找含 &lt;modules&gt; 的 pom」的历史兼容：
     * 那种猜测式锚点在换启动目录、换 IDE、拆模块后都会悄悄指向另一个目录，等于换了一把 KEK，
     * 库内 ssl_key_vault 的密文随之永久解不开（见过两次事故）。现在改成配置错误直接 fail-fast，
     * 宁可不启动，也不允许静默落第二套盘。</p>
     */
    static Path resolveStoreDir(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "csssl.store-dir 未配置：必须显式指定绝对路径（dev 见 application-dev.yml，容器见 compose 的 SSL_STORE_DIR）");
        }
        Path configuredPath = Path.of(configured);
        if (!configuredPath.isAbsolute()) {
            throw new IllegalStateException("csssl.store-dir 必须是绝对路径，当前为: " + configured);
        }
        return configuredPath.normalize().toAbsolutePath();
    }

    /**
     * 主密钥指纹（SHA-256 前 12 位十六进制）：只用于核对「各实例是不是同一把 KEK」，不可反推主密钥
     */
    private static String kekFingerprint(String masterKeyHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(HexUtil.decodeHex(masterKeyHex));
            return HexUtil.encodeHexStr(digest).substring(0, 12);
        } catch (Exception e) {
            return "unavailable(" + e.getClass().getSimpleName() + ")";
        }
    }
}
