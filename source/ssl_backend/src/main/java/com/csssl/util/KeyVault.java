package com.csssl.util;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.symmetric.SM4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * 密钥保险库：私钥 SM4 加密后落盘（key.pem.enc，hex 密文），杜绝私钥明文落盘。
 * <ul>
 *   <li>主密钥（master key）：32位十六进制（16字节 SM4 密钥），生产通过 SSL_MASTER_KEY 注入；
 *       未配置时首启生成随机密钥落盘 store-dir/master.key。</li>
 *   <li>封存（seal）：加密文件移名为 *.key.pem.enc.sealed，密钥进入离线状态，仅解封后可加载。</li>
 *   <li>兼容迁移：发现旧版明文 key.pem 时自动加密迁移并删除明文。</li>
 *   <li>HSM 预留：后续接入 GM/T 0018 服务器密码机时，以同类接口替换本类实现即可。</li>
 * </ul>
 */
public class KeyVault {

    private final Path storeDir;
    private final SM4 sm4;

    public KeyVault(Path storeDir, String masterKeyHex) {
        this.storeDir = storeDir;
        this.sm4 = new SM4(HexUtil.decodeHex(masterKeyHex));
    }

    /** 生成随机主密钥（32位十六进制） */
    public static String generateMasterKeyHex() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return HexUtil.encodeHexStr(key);
    }

    /** 私钥加密落盘（<relDir>/<name>.key.pem.enc） */
    public void storeKey(String relDir, String name, String keyPem) {
        try {
            Path dir = storeDir.resolve(relDir);
            Files.createDirectories(dir);
            Files.writeString(encFile(relDir, name), sm4.encryptHex(keyPem), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("私钥加密落盘失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载并解密私钥。
     * 返回 null 表示密钥不存在；密钥已封存或主密钥错误时抛出明确异常。
     */
    public String loadKey(String relDir, String name) {
        try {
            Path enc = encFile(relDir, name);
            if (Files.exists(enc)) {
                return sm4.decryptStr(Files.readString(enc, StandardCharsets.UTF_8).trim());
            }
            Path sealed = sealedFile(relDir, name);
            if (Files.exists(sealed)) {
                throw new IllegalStateException("密钥已封存离线（" + sealed + "），请先在管理端解封");
            }
            Path plain = plainFile(relDir, name);
            if (Files.exists(plain)) {
                // 旧版明文密钥：自动加密迁移并删除明文
                String pem = Files.readString(plain, StandardCharsets.UTF_8);
                Files.writeString(enc, sm4.encryptHex(pem), StandardCharsets.UTF_8);
                Files.delete(plain);
                return pem;
            }
            return null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("私钥解密失败（请核对主密钥 SSL_MASTER_KEY）: " + e.getMessage(), e);
        }
    }

    /** 密钥是否存在（含封存态） */
    public boolean keyExists(String relDir, String name) {
        return Files.exists(encFile(relDir, name)) || Files.exists(plainFile(relDir, name))
                || Files.exists(sealedFile(relDir, name));
    }

    /** 封存密钥：加密文件移名进入离线态 */
    public void sealKey(String relDir, String name) {
        try {
            Path enc = encFile(relDir, name);
            Path sealed = sealedFile(relDir, name);
            if (Files.exists(enc) && !Files.exists(sealed)) {
                Files.move(enc, sealed);
            }
        } catch (IOException e) {
            throw new IllegalStateException("密钥封存失败: " + e.getMessage(), e);
        }
    }

    /** 解封密钥：恢复在线态 */
    public void unsealKey(String relDir, String name) {
        try {
            Path enc = encFile(relDir, name);
            Path sealed = sealedFile(relDir, name);
            if (Files.exists(sealed) && !Files.exists(enc)) {
                Files.move(sealed, enc);
            }
        } catch (IOException e) {
            throw new IllegalStateException("密钥解封失败: " + e.getMessage(), e);
        }
    }

    /** 是否已封存 */
    public boolean isSealed(String relDir, String name) {
        return Files.exists(sealedFile(relDir, name));
    }

    /**
     * 删除密钥全部落盘形态（enc/plain/sealed）。
     * 仅用于「门限份额已完整落盘后删除单点根私钥」等显式安全切换场景，调用方须先确认新形态可用。
     */
    public void deleteKey(String relDir, String name) {
        try {
            for (Path p : new Path[]{encFile(relDir, name), plainFile(relDir, name), sealedFile(relDir, name)}) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new IllegalStateException("密钥删除失败: " + e.getMessage(), e);
        }
    }

    private Path encFile(String relDir, String name) {
        return storeDir.resolve(relDir).resolve(name + ".key.pem.enc");
    }

    private Path sealedFile(String relDir, String name) {
        return storeDir.resolve(relDir).resolve(name + ".key.pem.enc.sealed");
    }

    private Path plainFile(String relDir, String name) {
        return storeDir.resolve(relDir).resolve(name + ".key.pem");
    }
}
