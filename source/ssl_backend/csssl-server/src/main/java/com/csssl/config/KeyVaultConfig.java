package com.csssl.config;

import com.csssl.mapper.KeyVaultEntryMapper;
import com.csssl.util.KeyVault;
import cn.hutool.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URISyntaxException;
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
        // 把 storeDir 规范化为项目内绝对路径：相对路径 ./data/certs 基于 classpath root 解析，
        // 不再依赖 user.dir（IDEA 工作目录=项目根、mvn 工作目录=后端模块根，各落一套盘的老问题）。
        Path resolvedStoreDir = resolveStoreDir(props.getStoreDir());
        props.setStoreDir(resolvedStoreDir.toString());
        String masterKey = resolveMasterKey(props);
        log.info("[密钥保险库] default 域 store-dir={} KEK 指纹={}", resolvedStoreDir, kekFingerprint(masterKey));
        return new KeyVault(null, resolvedStoreDir, masterKey, mapper);
    }

    /**
     * 把配置里的 storeDir 解析为绝对路径。
     * 绝对路径原样返回；相对路径基于 classpath root（target/classes）向上推导到后端模块根，
     * 再拼接相对路径——开发环境下恒等于 source/ssl_backend/data/certs，与启动方式无关。
     * 生产打包后由 SSL_STORE_DIR 环境变量注入绝对路径，本方法不参与。
     */
    static Path resolveStoreDir(String configured) {
        Path configuredPath = Path.of(configured);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize().toAbsolutePath();
        }
        try {
            Path classpathRoot = Path.of(KeyVaultConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // classpathRoot = source/ssl_backend/target/classes/；往上 2 级 = source/ssl_backend/
            Path backendModuleRoot = classpathRoot.getParent().getParent();
            return backendModuleRoot.resolve(configuredPath).normalize().toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("store-dir 相对路径解析失败: " + e.getMessage(), e);
        }
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
