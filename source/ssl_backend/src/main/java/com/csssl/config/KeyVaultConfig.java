package com.csssl.config;

import com.csssl.entity.CaCompany;
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
 * 密钥保险库单例：CaService 与 TsaService 共用同一实例（default 公司域），
 * 消除各自 new KeyVault 的重复 SM4 初始化与主密钥首启生成竞态（master.key 语义不变）。
 * 私钥密文统一落库 ssl_key_vault（company_uscc 分域）；store-dir 仅保留主密钥文件、
 * 证书/CRL 公开材料与根密钥门限份额。注：根CA私钥刻意不落库（仅 root/key.pem.enc 文件形态 +
 * 门限份额），本保险库只承载 SUB_CA/签发/TSA/审计签名/接入密钥等。
 * 路线A多公司：{@link CompanyVaultResolver} 按 companyUscc 解析独立保险库——default 复用上述单例
 * （存量路径/主密钥零变化），其他公司主密钥独立生成落盘 companies/{companyUscc}/master.key。
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

    @Bean
    public CompanyVaultResolver companyVaultResolver(CssslProperties props, KeyVault keyVault, KeyVaultEntryMapper mapper) {
        return new CompanyVaultResolver(props, keyVault, mapper);
    }

    /**
     * 公司保险库解析器：存量单根（LEGACY_NAMESPACE）→ 现单例（SSL_MASTER_KEY/master.key，兼容铁律）；
 * 其他公司主密钥首启自动生成落盘 companies/{companyUscc}/master.key——刻意不复用环境变量主密钥，
 * 保证公司域间密钥材料完全隔离。私钥密文统一落库 ssl_key_vault，company_uscc 为公司域
 * （根CA私钥例外，仅文件形态，不进本表）。
     */
    public static class CompanyVaultResolver {

        private static final Pattern COMPANY_USCC_PATTERN =
                Pattern.compile("^([0-9A-HJ-NPQRTUWXY]{18}|default)$");

        private final CssslProperties props;
        private final KeyVault defaultVault;
        private final KeyVaultEntryMapper mapper;
        private final Map<String, KeyVault> pool = new ConcurrentHashMap<>();

        public CompanyVaultResolver(CssslProperties props, KeyVault defaultVault, KeyVaultEntryMapper mapper) {
            this.props = props;
            this.defaultVault = defaultVault;
            this.mapper = mapper;
        }

        /**
         * 公司标识校验（统一社会信用代码）：同时兜住路径穿越（字符集不含 . / 等）
         */
        public static void checkCompanyUscc(String companyUscc) {
            if (companyUscc == null || !COMPANY_USCC_PATTERN.matcher(companyUscc).matches()) {
                throw new IllegalArgumentException("非法公司标识（须为18位统一社会信用代码）: " + companyUscc);
            }
        }

        /**
         * 公司主密钥：仅文件形态（首启自动生成，语义同 default 域）
         */
        private static String resolveCompanyMasterKey(Path companyStoreDir) {
            Path mkFile = companyStoreDir.resolve("master.key");
            try {
                if (Files.exists(mkFile)) {
                    return Files.readString(mkFile, StandardCharsets.UTF_8).trim();
                }
                Files.createDirectories(mkFile.getParent());
                String generated = KeyVault.generateMasterKeyHex();
                Files.writeString(mkFile, generated, StandardCharsets.UTF_8);
                log.warn("[公司保险库] 已生成公司独立主密钥落盘 {}（须随该公司密钥材料一并备份，丢失即不可恢复）", mkFile);
                return generated;
            } catch (IOException e) {
                throw new IllegalStateException("公司主密钥初始化失败: " + e.getMessage(), e);
            }
        }

        /**
         * 公司落盘根目录：存量单根 → store-dir；其他 → store-dir/companies/{companyUscc}
         * （承载主密钥文件、证书/CRL 公开材料、根CA私钥文件与门限份额；SUB_CA/签发等私钥密文统一落库）
         */
        public Path companyStoreDir(String companyUscc) {
            checkCompanyUscc(companyUscc);
            return CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                    ? Path.of(props.getStoreDir())
                    : Path.of(props.getStoreDir(), "companies", companyUscc);
        }

        /**
         * 取公司保险库（公司不存在时按需构建并缓存；存量单根恒返回共享单例）
         */
        public KeyVault forCompany(String companyUscc) {
            if (CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
                return defaultVault;
            }
            checkCompanyUscc(companyUscc);
            return pool.computeIfAbsent(companyUscc,
                    k -> new KeyVault(k, companyStoreDir(k), resolveCompanyMasterKey(companyStoreDir(k)), mapper));
        }
    }
}
