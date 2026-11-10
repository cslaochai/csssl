package com.csssl.util;

import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 对外 API 非对称验签：调用方持 SM2 私钥签名，CA 用登记的公钥验签。
 *
 * <p>与推送验签（{@code CaPushVerifier}）同一套算法与签名串口径，区别只在签名串内容：
 * <pre>
 * appId \n timestamp \n nonce \n METHOD \n pathWithQuery \n sha256Hex(body)
 * </pre>
 * 签名值 Base64；公钥为 130 字符 hex、04 前缀的未压缩点。</p>
 *
 * <p>这样 CA 侧不再需要保存调用方的共享口令：私钥只在调用方手里，
 * 公钥泄露也只能验签、不能伪造请求。</p>
 */
public final class CaApiSign {

    private static final ECDomainParameters DOMAIN;
    /** 未压缩公钥点：04 + 64 位 X + 64 位 Y */
    private static final int PUBLIC_KEY_HEX_LEN = 130;

    static {
        X9ECParameters params = GMNamedCurves.getByName("sm2p256v1");
        DOMAIN = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }

    private CaApiSign() {
    }

    /**
     * 构造待签名串（六行，LF 分隔，末尾不加换行）。
     */
    public static String canonical(String appId, String timestamp, String nonce,
                                   String method, String pathWithQuery, String bodySha256Hex) {
        return appId + "\n" + timestamp + "\n" + nonce + "\n"
                + method.toUpperCase() + "\n" + pathWithQuery + "\n" + bodySha256Hex;
    }

    /**
     * 公钥格式校验：130 字符、04 前缀、解码后落在 SM2 曲线上。
     */
    public static boolean validPublicKey(String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.isBlank()) {
            return false;
        }
        String key = publicKeyHex.trim().toLowerCase();
        if (key.length() != PUBLIC_KEY_HEX_LEN || !key.startsWith("04")) {
            return false;
        }
        try {
            ECPoint point = DOMAIN.getCurve().decodePoint(Hex.decode(key));
            return point != null && !point.isInfinity();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验签。
     *
     * @param publicKeyHex    登记的公钥（130 hex）
     * @param canonical       待验签串
     * @param signatureBase64 请求头 X-Signature
     * @return true=签名有效
     */
    public static boolean verify(String publicKeyHex, String canonical, String signatureBase64) {
        if (!validPublicKey(publicKeyHex) || canonical == null || signatureBase64 == null) {
            return false;
        }
        try {
            ECPoint point = DOMAIN.getCurve().decodePoint(Hex.decode(publicKeyHex.trim()));
            ECPublicKeyParameters publicKey = new ECPublicKeyParameters(point, DOMAIN);
            SM2Signer signer = new SM2Signer();
            signer.init(false, publicKey);
            byte[] data = canonical.getBytes(StandardCharsets.UTF_8);
            signer.update(data, 0, data.length);
            return signer.verifySignature(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 公钥指纹前 16 位，用于展示"当前登记的是哪把公钥"。
     */
    public static String keyId(String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.isBlank()) {
            return null;
        }
        String key = publicKeyHex.trim().toLowerCase();
        return key.length() >= 16 ? key.substring(0, 16) : key;
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return Hex.toHexString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
