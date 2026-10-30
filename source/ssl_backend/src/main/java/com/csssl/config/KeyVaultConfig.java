package com.csssl.config;

import com.csssl.entity.CaTenant;
import com.csssl.mapper.KeyVaultEntryMapper;
import com.csssl.util.KeyVault;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 密钥保险库单例：CaService 与 TsaService 共用同一实例（default 租户域），
 * 消除各自 new KeyVault 的重复 SM4 初始化与主密钥首启生成竞态（master.key 语义不变）。
 * 私钥密文统一落库 ssl_key_vault（tenant_key 分域）；store-dir 仅保留主密钥文件、
 * 证书/CRL 公开材料与根密钥门限份额。
 * 路线A多租户：{@link TenantVaultResolver} 按 tenantKey 解析独立保险库——default 复用上述单例
 * （存量路径/主密钥零变化），其他租户主密钥独立生成落盘 tenants/{tenantKey}/master.key。
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
        return new KeyVault(CaTenant.LEGACY_TENANT_KEY, Path.of(props.getStoreDir()),
                resolveMasterKey(props), mapper);
    }

    @Bean
    public TenantVaultResolver tenantVaultResolver(CssslProperties props, KeyVault keyVault, KeyVaultEntryMapper mapper) {
        return new TenantVaultResolver(props, keyVault, mapper);
    }

    /**
     * 租户保险库解析器：存量单根（LEGACY_TENANT_KEY）→ 现单例（SSL_MASTER_KEY/master.key，兼容铁律）；
     * 其他租户主密钥首启自动生成落盘 tenants/{tenantKey}/master.key——刻意不复用环境变量主密钥，
     * 保证租户域间密钥材料完全隔离。私钥密文统一落库 ssl_key_vault，tenant_key 为租户域。
     */
    public static class TenantVaultResolver {

        private static final Pattern TENANT_KEY_PATTERN = Pattern.compile("^[a-z0-9]{1,32}$");

        private final CssslProperties props;
        private final KeyVault defaultVault;
        private final KeyVaultEntryMapper mapper;
        private final Map<String, KeyVault> pool = new ConcurrentHashMap<>();

        public TenantVaultResolver(CssslProperties props, KeyVault defaultVault, KeyVaultEntryMapper mapper) {
            this.props = props;
            this.defaultVault = defaultVault;
            this.mapper = mapper;
        }

        /**
         * 租户标识格式校验（同时兜住路径穿越：仅小写字母数字）
         */
        public static void checkTenantKey(String tenantKey) {
            if (tenantKey == null || !TENANT_KEY_PATTERN.matcher(tenantKey).matches()) {
                throw new IllegalArgumentException("非法租户标识（仅允许1-32位小写字母数字）: " + tenantKey);
            }
        }

        /**
         * 租户主密钥：仅文件形态（首启自动生成，语义同 default 域）
         */
        private static String resolveTenantMasterKey(Path tenantStoreDir) {
            Path mkFile = tenantStoreDir.resolve("master.key");
            try {
                if (Files.exists(mkFile)) {
                    return Files.readString(mkFile, StandardCharsets.UTF_8).trim();
                }
                Files.createDirectories(mkFile.getParent());
                String generated = KeyVault.generateMasterKeyHex();
                Files.writeString(mkFile, generated, StandardCharsets.UTF_8);
                log.warn("[租户保险库] 已生成租户独立主密钥落盘 {}（须随该租户密钥材料一并备份，丢失即不可恢复）", mkFile);
                return generated;
            } catch (IOException e) {
                throw new IllegalStateException("租户主密钥初始化失败: " + e.getMessage(), e);
            }
        }

        /**
         * 租户落盘根目录：存量单根 → store-dir；其他 → store-dir/tenants/{tenantKey}
         * （仅承载主密钥文件与证书/CRL 公开材料；私钥密文已统一落库）
         */
        public Path tenantStoreDir(String tenantKey) {
            checkTenantKey(tenantKey);
            return CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)
                    ? Path.of(props.getStoreDir())
                    : Path.of(props.getStoreDir(), "tenants", tenantKey);
        }

        /**
         * 取租户保险库（租户不存在时按需构建并缓存；存量单根恒返回共享单例）
         */
        public KeyVault forTenant(String tenantKey) {
            if (CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)) {
                return defaultVault;
            }
            checkTenantKey(tenantKey);
            return pool.computeIfAbsent(tenantKey,
                    k -> new KeyVault(k, tenantStoreDir(k), resolveTenantMasterKey(tenantStoreDir(k)), mapper));
        }
    }
}
