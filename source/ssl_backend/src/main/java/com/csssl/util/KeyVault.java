package com.csssl.util;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.symmetric.SM4;
import com.csssl.entity.KeyVaultEntry;
import com.csssl.mapper.KeyVaultEntryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * 密钥保险库：私钥 SM4 加密后落库（表 ssl_key_vault，hex 密文），杜绝私钥明文落盘。
 * <ul>
 *   <li>主密钥（KEK/master key）：32位十六进制（16字节 SM4 密钥），刻意不入库——生产通过
 *       SSL_MASTER_KEY 注入或 store-dir/master.key 文件，与密文异存储，拖库拿不到明文。</li>
 *   <li>封存（seal）：条目 sealed=1 进入离线状态，仅解封后可加载（语义同原 .sealed 文件改名）。</li>
 *   <li>存量迁移：发现旧版文件形态（key.pem.enc / .sealed / 明文 key.pem）时按需迁移入库，
 *       原文件改名 *.migrated 留档；default 域密文与原文件共用同一主密钥，密文可原样搬。</li>
 *   <li>路线A多租户：每租户域一个实例——default 为存量单根保险库，其他租户主密钥独立
 *       （tenants/{tenantKey}/master.key），租户间不共享密钥材料。</li>
 *   <li>HSM 预留：后续接入 GM/T 0018 服务器密码机时，以同类接口替换本类实现即可。</li>
 * </ul>
 */
@Slf4j
public class KeyVault {

    private final String scopeTenantKey;
    private final Path legacyStoreDir;
    private final SM4 sm4;
    private final KeyVaultEntryMapper mapper;

    public KeyVault(String scopeTenantKey, Path legacyStoreDir, String masterKeyHex, KeyVaultEntryMapper mapper) {
        this.scopeTenantKey = scopeTenantKey;
        this.legacyStoreDir = legacyStoreDir;
        this.sm4 = new SM4(HexUtil.decodeHex(masterKeyHex));
        this.mapper = mapper;
    }

    /** 生成随机主密钥（32位十六进制） */
    public static String generateMasterKeyHex() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return HexUtil.encodeHexStr(key);
    }

    /** 私钥加密入库（ssl_key_vault，sealed 复位为在线态） */
    public void storeKey(String relDir, String name, String keyPem) {
        String cipher = sm4.encryptHex(keyPem);
        try {
            KeyVaultEntry entry = migratedEntry(relDir, name);
            if (entry == null) {
                entry = newEntry(relDir, name, cipher, 0);
                insert(entry);
            } else {
                entry.setCipherHex(cipher);
                entry.setSealed(0);
                mapper.updateById(entry);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("私钥密文入库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载并解密私钥。
     * 返回 null 表示密钥不存在；密钥已封存或主密钥错误时抛出明确异常。
     */
    public String loadKey(String relDir, String name) {
        KeyVaultEntry entry = migratedEntry(relDir, name);
        if (entry == null) {
            return null;
        }
        if (entry.getSealed() != null && entry.getSealed() == 1) {
            throw new IllegalStateException("密钥已封存离线（" + scopeTenantKey + "/" + relDir + "/" + name + "），请先在管理端解封");
        }
        try {
            return sm4.decryptStr(entry.getCipherHex().trim());
        } catch (Exception e) {
            throw new IllegalStateException("私钥解密失败（请核对主密钥 SSL_MASTER_KEY）: " + e.getMessage(), e);
        }
    }

    /** 密钥是否存在（含封存态） */
    public boolean keyExists(String relDir, String name) {
        return migratedEntry(relDir, name) != null;
    }

    /** 封存密钥：条目置 sealed=1 进入离线态 */
    public void sealKey(String relDir, String name) {
        KeyVaultEntry entry = migratedEntry(relDir, name);
        if (entry != null && (entry.getSealed() == null || entry.getSealed() == 0)) {
            entry.setSealed(1);
            mapper.updateById(entry);
        }
    }

    /** 解封密钥：恢复在线态 */
    public void unsealKey(String relDir, String name) {
        KeyVaultEntry entry = migratedEntry(relDir, name);
        if (entry != null && entry.getSealed() != null && entry.getSealed() == 1) {
            entry.setSealed(0);
            mapper.updateById(entry);
        }
    }

    /** 是否已封存 */
    public boolean isSealed(String relDir, String name) {
        KeyVaultEntry entry = migratedEntry(relDir, name);
        return entry != null && entry.getSealed() != null && entry.getSealed() == 1;
    }

    /**
     * 删除密钥全部形态（库内行 + 存量文件 enc/plain/sealed/migrated）。
     * 仅用于「门限份额已完整落盘后删除单点根私钥」等显式安全切换场景，调用方须先确认新形态可用。
     */
    public void deleteKey(String relDir, String name) {
        KeyVaultEntry entry = mapper.selectEntry(scopeTenantKey, relDir, name);
        if (entry != null) {
            mapper.deleteById(entry.getId());
        }
        for (String suffix : new String[]{".key.pem.enc", ".key.pem.enc.sealed", ".key.pem",
                ".key.pem.enc.migrated", ".key.pem.migrated"}) {
            deleteQuietly(legacyFile(relDir, name, suffix));
        }
    }

    /**
     * 取库内条目；库内缺失但存量文件形态还在时，就地迁移入库（密文原样搬，明文形态先加密）。
     * 迁移后原文件改名 *.migrated 留档，库成为唯一事实源。
     */
    private KeyVaultEntry migratedEntry(String relDir, String name) {
        KeyVaultEntry entry = mapper.selectEntry(scopeTenantKey, relDir, name);
        if (entry != null) {
            return entry;
        }
        Path enc = legacyFile(relDir, name, ".key.pem.enc");
        Path sealed = legacyFile(relDir, name, ".key.pem.enc.sealed");
        Path plain = legacyFile(relDir, name, ".key.pem");
        try {
            if (Files.exists(enc) || Files.exists(sealed)) {
                Path src = Files.exists(enc) ? enc : sealed;
                String cipher = Files.readString(src, StandardCharsets.UTF_8).trim();
                insert(newEntry(relDir, name, cipher, Files.exists(sealed) ? 1 : 0));
                renameMigrated(src);
                log.info("[密钥保险库][{}] 存量密文文件已迁移入库: {}/{}", scopeTenantKey, relDir, name);
                return mapper.selectEntry(scopeTenantKey, relDir, name);
            }
            if (Files.exists(plain)) {
                String pem = Files.readString(plain, StandardCharsets.UTF_8);
                insert(newEntry(relDir, name, sm4.encryptHex(pem), 0));
                renameMigrated(plain);
                log.info("[密钥保险库][{}] 存量明文私钥文件已加密迁移入库: {}/{}", scopeTenantKey, relDir, name);
                return mapper.selectEntry(scopeTenantKey, relDir, name);
            }
        } catch (IOException e) {
            throw new IllegalStateException("存量密钥文件迁移入库失败: " + e.getMessage(), e);
        }
        return null;
    }

    private KeyVaultEntry newEntry(String relDir, String name, String cipherHex, int sealed) {
        KeyVaultEntry entry = new KeyVaultEntry();
        entry.setTenantKey(scopeTenantKey);
        entry.setRelDir(relDir);
        entry.setName(name);
        entry.setCipherHex(cipherHex);
        entry.setSealed(sealed);
        return entry;
    }

    private void insert(KeyVaultEntry entry) {
        try {
            mapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // 并发迁移撞唯一键：以已入库行为准
        }
    }

    private Path legacyFile(String relDir, String name, String suffix) {
        return legacyStoreDir.resolve(relDir).resolve(name + suffix);
    }

    private void renameMigrated(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".migrated"));
        } catch (IOException e) {
            throw new IllegalStateException("存量密钥文件改名留档失败: " + file + ": " + e.getMessage(), e);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
