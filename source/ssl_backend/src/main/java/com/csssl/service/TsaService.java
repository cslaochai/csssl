package com.csssl.service;

import com.csssl.util.CertUtil;
import com.csssl.util.KeyVault;
import com.csssl.util.PemUtil;
import com.csssl.vo.TsaPublicKeyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 可信时间戳（TSA）服务：对内容摘要签发时间戳令牌（对齐 RFC 3161 时间戳语义——
 * 令牌把「摘要 + 授时时刻 + 序列号」绑定并由 TSA 独立密钥签名，可对抗本机时钟篡改）。
 *
 * <p>TSA 密钥为独立 SM2 国密密钥对（与根/SUB_CA 分离），私钥经 KeyVault SM4 加密落盘，
 * 公钥 PEM 一并落盘（供 HIS 等业务系统下载验签）；令牌签名算法 SM3withSM2
 * （对齐 WS/T 847 医学电子签名国密要求）。
 * 令牌规范化串（HIS 验签复用同一格式）：{@code CSSSL-TSA-V1|serial|digestHex|tsaTime}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TsaService {

    /**
     * 令牌规范化串版本前缀（格式变更必须换版本号，否则历史令牌验不过）
     */
    public static final String CANONICAL_PREFIX = "CSSSL-TSA-V1";

    public static final String SERIAL_PREFIX = "TSA";

    public static final String TSA_ALGORITHM = "SM2";

    public static final String TSA_SIGN_ALGO = "SM3withSM2";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern DIGEST_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final AuditService auditService;
    /**
     * 单例保险库 bean（KeyVaultConfig 提供），与 CaService 共用同一 store-dir 实例
     */
    private final KeyVault vault;

    private final java.security.SecureRandom random = new java.security.SecureRandom();

    private volatile String cachedPublicPem;

    /**
     * 令牌规范化串（与 HIS 验签共用同一格式，两处必须一致）
     */
    public static String canonical(String serial, String digestHex, LocalDateTime tsaTime) {
        return CANONICAL_PREFIX + "|" + serial + "|" + digestHex.toLowerCase() + "|" + ISO.format(tsaTime);
    }

    /**
     * 确保 TSA 密钥就绪（幂等）：SM2 密钥对，私钥 SM4 加密落盘，公钥 PEM 一并缓存
     */
    private synchronized void ensureKey() {
        if (cachedPublicPem != null) {
            return;
        }
        if (!vault.keyExists("tsa", "key")) {
            KeyPair kp = CertUtil.generateKeyPair(TSA_ALGORITHM);
            vault.storeKey("tsa", "key", PemUtil.keyToPem(kp.getPrivate()));
            vault.storeKey("tsa", "pub", PemUtil.toPem("PUBLIC KEY", kp.getPublic().getEncoded()));
            log.info("[TSA] 已生成独立 SM2 时间戳密钥（私钥经 SM4 加密落盘，公钥落盘供业务系统下载）");
        }
        cachedPublicPem = vault.loadKey("tsa", "pub");
    }

    /**
     * TSA 公钥（PEM），供业务系统验签时间戳令牌
     */
    public String publicKeyPem() {
        ensureKey();
        return cachedPublicPem;
    }

    /**
     * TSA 公钥指纹（SM3，人工核对用）
     */
    public String publicKeyFingerprint() {
        ensureKey();
        try {
            PublicKey key = PemUtil.parsePublicKey(cachedPublicPem);
            MessageDigest md = MessageDigest.getInstance("SM3", "BC");
            return Hex.toHexString(md.digest(key.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("TSA 公钥指纹计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对内容摘要盖一枚可信时间戳（令牌自证，无状态服务）
     */
    public Stamp stamp(String digestHex) {
        if (digestHex == null || !DIGEST_HEX.matcher(digestHex.trim()).matches()) {
            throw new IllegalArgumentException("摘要必须为 64 位十六进制（SHA-256/SM3 摘要）");
        }
        ensureKey();
        String digest = digestHex.trim().toLowerCase();
        // 授时时刻取秒级（无纳秒）：HIS 落库回读后令牌规范化串仍可逐字对齐
        LocalDateTime tsaTime = LocalDateTime.now(ZoneId.systemDefault()).withNano(0);
        // 序列号带秒级时间 + 随机后缀：进程重启（内存序列归零）后也不会与历史令牌撞号，
        // 保证业务系统侧台账（serial 为唯一键）始终能落库
        String serial = SERIAL_PREFIX + SECOND.format(tsaTime)
                + String.format("%02x%02x", random.nextInt(256), random.nextInt(256));
        String canonical = canonical(serial, digest, tsaTime);
        String token = sign(canonical);
        log.info("[TSA] 盖章 serial={} 摘要={}… 时刻={} 算法={}", serial, digest.substring(0, 8), tsaTime, TSA_SIGN_ALGO);
        return new Stamp(serial, tsaTime, token, TSA_SIGN_ALGO);
    }

    /**
     * 校验一枚令牌是否由本 TSA 签发且与摘要/时刻一致（管理端自验用；业务侧验签在 HIS 本地用公钥）
     */
    public boolean verifyToken(String serial, String digestHex, LocalDateTime tsaTime, String tokenValue) {
        try {
            ensureKey();
            PublicKey key = PemUtil.parsePublicKey(cachedPublicPem);
            Signature sig = Signature.getInstance(TSA_SIGN_ALGO, "BC");
            sig.initVerify(key);
            sig.update(canonical(serial, digestHex, tsaTime).getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(tokenValue));
        } catch (Exception e) {
            log.warn("[TSA] 令牌校验异常: {}", e.getMessage());
            return false;
        }
    }

    private String sign(String canonical) {
        try {
            PrivateKey key = PemUtil.parsePrivateKey(vault.loadKey("tsa", "key"));
            Signature sig = Signature.getInstance(TSA_SIGN_ALGO, "BC");
            sig.initSign(key);
            sig.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("TSA 令牌签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * 供管理端/探针展示 TSA 状态
     */
    public TsaPublicKeyVO status() {
        ensureKey();
        TsaPublicKeyVO vo = new TsaPublicKeyVO();
        vo.setAlgorithm(TSA_ALGORITHM);
        vo.setSignAlgo(TSA_SIGN_ALGO);
        vo.setPublicKeyPem(cachedPublicPem);
        vo.setFingerprint(publicKeyFingerprint());
        vo.setNote("CSSSL 院内可信时间戳：SM3withSM2 令牌，私钥 SM4 加密落盘");
        return vo;
    }

    /**
     * 审计留痕（盖章动作）
     */
    public void audit(String username, String serial, String digestPrefix, String ip) {
        try {
            auditService.record(username == null ? "ANON" : username, "TSA_STAMP",
                    serial, "时间戳盖章：摘要 " + digestPrefix + "…", ip);
        } catch (Exception e) {
            log.warn("[TSA] 审计记录失败（不影响盖章）: {}", e.getMessage());
        }
    }

    /**
     * 一次盖章结果（HIS 侧直接落台账的字段）
     */
    public record Stamp(String serial, LocalDateTime tsaTime, String tokenValue, String algo) {
    }
}
