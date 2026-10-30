package com.csssl.config;

import com.csssl.entity.CaOrg;
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
 * 密钥保险库单例：CaService 与 TsaService 共用同一 store-dir 下的同一 KeyVault 实例，
 * 消除各自 new KeyVault 的重复 SM4 初始化与 master.key 首启生成竞态（master.key 语义不变）。
 * 路线A多机构：{@link OrgVaultResolver} 按 orgKey 解析独立保险库——default 复用上述单例
 * （存量路径/主密钥零变化），其他机构以 store-dir/orgs/{orgKey} 为根、主密钥独立生成。
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
    public KeyVault keyVault(CssslProperties props) {
        return new KeyVault(Path.of(props.getStoreDir()), resolveMasterKey(props));
    }

    @Bean
    public OrgVaultResolver orgVaultResolver(CssslProperties props, KeyVault keyVault) {
        return new OrgVaultResolver(props, keyVault);
    }

    /**
     * 机构保险库解析器：存量单根（LEGACY_ORG_KEY）→ 现单例（store-dir + SSL_MASTER_KEY/master.key，兼容铁律）；
     * 其他机构 → store-dir/orgs/{orgKey} 为根的独立保险库，主密钥首启自动生成落盘
     * orgs/{orgKey}/master.key——刻意不复用环境变量主密钥，保证机构域间密钥材料完全隔离。
     */
    public static class OrgVaultResolver {

        private static final Pattern ORG_KEY_PATTERN = Pattern.compile("^[a-z0-9]{1,32}$");

        private final CssslProperties props;
        private final KeyVault defaultVault;
        private final Map<String, KeyVault> pool = new ConcurrentHashMap<>();

        public OrgVaultResolver(CssslProperties props, KeyVault defaultVault) {
            this.props = props;
            this.defaultVault = defaultVault;
        }

        /**
         * 机构标识格式校验（同时兜住路径穿越：仅小写字母数字）
         */
        public static void checkOrgKey(String orgKey) {
            if (orgKey == null || !ORG_KEY_PATTERN.matcher(orgKey).matches()) {
                throw new IllegalArgumentException("非法机构标识（仅允许1-32位小写字母数字）: " + orgKey);
            }
        }

        /**
         * 机构主密钥：仅文件形态（首启自动生成，语义同 default 域）
         */
        private static String resolveOrgMasterKey(Path orgStoreDir) {
            Path mkFile = orgStoreDir.resolve("master.key");
            try {
                if (Files.exists(mkFile)) {
                    return Files.readString(mkFile, StandardCharsets.UTF_8).trim();
                }
                Files.createDirectories(mkFile.getParent());
                String generated = KeyVault.generateMasterKeyHex();
                Files.writeString(mkFile, generated, StandardCharsets.UTF_8);
                log.warn("[机构保险库] 已生成机构独立主密钥落盘 {}（须随该机构密钥材料一并备份，丢失即不可恢复）", mkFile);
                return generated;
            } catch (IOException e) {
                throw new IllegalStateException("机构主密钥初始化失败: " + e.getMessage(), e);
            }
        }

        /**
         * 机构落盘根目录：存量单根 → store-dir；其他 → store-dir/orgs/{orgKey}
         */
        public Path orgStoreDir(String orgKey) {
            checkOrgKey(orgKey);
            return CaOrg.LEGACY_ORG_KEY.equals(orgKey)
                    ? Path.of(props.getStoreDir())
                    : Path.of(props.getStoreDir(), "orgs", orgKey);
        }

        /**
         * 取机构保险库（机构不存在时按需构建并缓存；存量单根恒返回共享单例）
         */
        public KeyVault forOrg(String orgKey) {
            if (CaOrg.LEGACY_ORG_KEY.equals(orgKey)) {
                return defaultVault;
            }
            checkOrgKey(orgKey);
            return pool.computeIfAbsent(orgKey,
                    k -> new KeyVault(orgStoreDir(k), resolveOrgMasterKey(orgStoreDir(k))));
        }
    }
}
