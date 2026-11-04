package com.csssl.config;

import com.csssl.entity.CaCompany;
import com.csssl.mapper.KeyVaultEntryMapper;
import com.csssl.util.KeyVault;
import com.csssl.util.UsccValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公司保险库解析器：按 companyUscc 解析各自独立的 {@link KeyVault} 实例，实现公司域间密钥材料完全隔离。
 *
 * <ul>
 *   <li>存量单根（{@link CaCompany#LEGACY_NAMESPACE}）→ 复用共享单例 defaultVault（存量路径/主密钥零变化）。</li>
 *   <li>其他公司主密钥首启自动生成落盘 companies/{companyUscc}/master.key——刻意不复用环境变量主密钥。</li>
 *   <li>私钥密文统一落库 ssl_key_vault，company_uscc 为公司域（根CA私钥例外，仅文件形态，不进本表）。</li>
 * </ul>
 *
 * 公司标识（统一社会信用代码）的合法性校验已下沉到 {@link UsccValidator}，本类只调用、不再自持校验逻辑。
 * 原内嵌于 {@code KeyVaultConfig} 的嵌套类，现独立为组件，让配置类回归纯粹的 bean 装配职责。
 */
@Slf4j
@Component
public class CompanyVaultResolver {

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
     * 公司落盘根目录：存量单根 → store-dir；其他 → store-dir/companies/{companyUscc}
     * （承载主密钥文件、证书/CRL 公开材料、根CA私钥文件与门限份额；SUB_CA/签发等私钥密文统一落库）
     */
    public Path companyStoreDir(String companyUscc) {
        UsccValidator.checkCompanyUscc(companyUscc);
        return CaCompany.LEGACY_NAMESPACE.equals(companyUscc)
                ? Path.of(props.getStoreDir())
                : Path.of(props.getStoreDir(), "companies", companyUscc);
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
     * 取公司保险库（公司不存在时按需构建并缓存；存量单根恒返回共享单例）
     */
    public KeyVault forCompany(String companyUscc) {
        if (CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            return defaultVault;
        }
        UsccValidator.checkCompanyUscc(companyUscc);
        return pool.computeIfAbsent(companyUscc,
                k -> new KeyVault(k, companyStoreDir(k), resolveCompanyMasterKey(companyStoreDir(k)), mapper));
    }
}
