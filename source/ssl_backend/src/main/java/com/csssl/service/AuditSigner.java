package com.csssl.service;

import cn.hutool.core.util.HexUtil;
import com.csssl.config.CssslProperties;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * 审计日志签名器（GM/T 0034 §10.1/§10.5：操作记录执行人数字签名、审计记录创建人数字签名、日志不可修改）。
 *
 * <p>审计专用独立 SM2 密钥（与 CA/登录密钥隔离）：私钥 SM4 加密落盘（store-dir/audit-sign/），
 * 公钥落盘供验签。审计记录写入时对规范化串做 SM3withSM2 签名，事后任何人改动字段都会验签失败，
 * 实现"日志防篡改 + 责任到人"。</p>
 */
@Slf4j
@Component
public class AuditSigner {

    /** 审计签名算法（GM/T 0034 国密体系） */
    public static final String SIGN_ALGO = "SM3withSM2";

    /** 审计密钥相对目录（store-dir 下） */
    private static final String KEY_REL = "audit-sign";
    private static final String KEY_NAME = "key";
    private static final String PUB_NAME = "pub";

    private final CssslProperties props;
    private volatile com.csssl.util.KeyVault vault;
    private volatile String cachedPublicPem;

    public AuditSigner(CssslProperties props) {
        this.props = props;
    }

    private com.csssl.util.KeyVault vault() {
        if (vault == null) {
            synchronized (this) {
                if (vault == null) {
                    vault = new com.csssl.util.KeyVault(Path.of(props.getStoreDir()), masterKey());
                }
            }
        }
        return vault;
    }

    private String masterKey() {
        String configured = props.getKeyVault().getMasterKey();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            Path mkFile = Path.of(props.getStoreDir()).resolve("master.key");
            if (Files.exists(mkFile)) {
                return Files.readString(mkFile, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            throw new IllegalStateException("审计签名主密钥读取失败: " + e.getMessage(), e);
        }
        throw new IllegalStateException("审计签名主密钥未配置（store-dir/master.key 不存在且未设 SSL_MASTER_KEY）");
    }

    /** 确保审计签名密钥就绪（幂等）：首次生成 SM2 密钥对，私钥 SM4 加密落盘，公钥落盘 */
    private synchronized void ensureKey() {
        if (cachedPublicPem != null) {
            return;
        }
        if (!vault().keyExists(KEY_REL, KEY_NAME)) {
            KeyPair kp = CertUtil.generateKeyPair("SM2");
            vault().storeKey(KEY_REL, KEY_NAME, PemUtil.keyToPem(kp.getPrivate()));
            vault().storeKey(KEY_REL, PUB_NAME, PemUtil.toPem("PUBLIC KEY", kp.getPublic().getEncoded()));
            log.info("[审计签名] 已生成独立 SM2 审计签名密钥（私钥 SM4 加密落盘，公钥供验签）");
        }
        cachedPublicPem = vault().loadKey(KEY_REL, PUB_NAME);
    }

    /** 审计签名公钥 PEM（供外部/管理端验签） */
    public String publicKeyPem() {
        ensureKey();
        return cachedPublicPem;
    }

    /** 审计密钥是否已就绪（供状态展示） */
    public boolean ready() {
        try {
            ensureKey();
            return true;
        } catch (Exception e) {
            log.warn("[审计签名] 密钥未就绪: {}", e.getMessage());
            return false;
        }
    }

    /** 对审计规范化串签名，返回 SM3withSM2 签名（十六进制） */
    public String sign(String canonical) {
        try {
            ensureKey();
            PrivateKey key = PemUtil.parsePrivateKey(vault().loadKey(KEY_REL, KEY_NAME));
            Signature sig = Signature.getInstance(SIGN_ALGO, "BC");
            sig.initSign(key);
            sig.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexUtil.encodeHexStr(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("审计签名失败: " + e.getMessage(), e);
        }
    }

    /** 验签：规范化串 + 签名值 → 是否由本审计密钥签发且内容未被篡改 */
    public boolean verify(String canonical, String signHex) {
        try {
            ensureKey();
            PublicKey key = PemUtil.parsePublicKey(cachedPublicPem);
            Signature sig = Signature.getInstance(SIGN_ALGO, "BC");
            sig.initVerify(key);
            sig.update(canonical.getBytes(StandardCharsets.UTF_8));
            return sig.verify(HexUtil.decodeHex(signHex));
        } catch (Exception e) {
            log.warn("[审计签名] 验签异常: {}", e.getMessage());
            return false;
        }
    }
}
