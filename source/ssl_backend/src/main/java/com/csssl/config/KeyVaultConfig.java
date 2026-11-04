package com.csssl.config;

import com.csssl.mapper.KeyVaultEntryMapper;
import com.csssl.util.KeyVault;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        return new KeyVault(null, Path.of(props.getStoreDir()),
                resolveMasterKey(props), mapper);
    }
}
