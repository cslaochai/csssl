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
        String masterKey = resolveMasterKey(props);
        Path storeDir = Path.of(props.getStoreDir()).toAbsolutePath().normalize();
        // 启动即打印「KEK 所在盘 + 主密钥指纹」：换启动目录/换 SSL_STORE_DIR 会换一套 KEK，
        // 而 ssl_key_vault 是跨实例共享的库，密文只认写入时那套 KEK。不在这里显式留痕，
        // 症状会拖到某个接口解密失败才暴露（BadPaddingException: pad block corrupted）。
        log.info("[密钥保险库] default 域 store-dir={} KEK 指纹={}", storeDir, kekFingerprint(masterKey));
        return new KeyVault(null, Path.of(props.getStoreDir()), masterKey, mapper);
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
