package com.csssl.util;

import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * SM2 密钥对工具：生成、公钥推导、合法性校验。
 *
 * <p>应用启动时<b>不再</b>自动生成任何推送密钥——签名私钥必须固定且跨重启不变，
 * 所以生成动作只在运维手工执行时发生：</p>
 *
 * <pre>
 * java -cp &lt;target/classes&gt;:&lt;bcprov-jar&gt; com.csssl.util.Sm2KeyTool
 * 输出：
 *   privateKeyHex=...
 *   publicKeyHex=...（配到对端验签方）
 *   keyId=...
 * </pre>
 *
 * <p>也可以传一个已配的私钥，只打印它推导出的公钥，用来核对两端配的是不是同一对：</p>
 *
 * <pre>java ... com.csssl.util.Sm2KeyTool &lt;privateKeyHex&gt;</pre>
 */
public final class Sm2KeyTool {

    /** 未压缩公钥点前缀 */
    private static final String POINT_PREFIX = "04";
    /** 私钥 64 hex（32 字节） */
    private static final Pattern PRIVATE_HEX = Pattern.compile("^[0-9a-f]{64}$");
    /** 未压缩公钥 130 hex（04 + X + Y，各 32 字节） */
    private static final Pattern PUBLIC_HEX = Pattern.compile("^04[0-9a-f]{128}$");

    private static final ECDomainParameters DOMAIN = sm2Domain();

    private Sm2KeyTool() {
    }

    /** 生成一对 SM2 密钥，返回 [私钥 hex, 公钥 hex(04 前缀)] */
    public static String[] generate() {
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(DOMAIN, new SecureRandom()));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        return new String[]{leftPad64(priv.getD().toString(16)), pointToHex(pub.getQ())};
    }

    /** 由私钥推导公钥：Q = dG */
    public static String derivePublicKey(String privateKeyHex) {
        ECPoint q = DOMAIN.getG().multiply(new BigInteger(privateKeyHex, 16)).normalize();
        return pointToHex(q);
    }

    /** 私钥合法性：64 位十六进制，且 0 &lt; d &lt; n */
    public static boolean isValidPrivateKey(String privateKeyHex) {
        if (privateKeyHex == null || !PRIVATE_HEX.matcher(privateKeyHex).matches()) {
            return false;
        }
        BigInteger d = new BigInteger(privateKeyHex, 16);
        return d.signum() > 0 && d.compareTo(DOMAIN.getN()) < 0;
    }

    /** 未压缩公钥合法性：130 位十六进制、04 前缀 */
    public static boolean isValidPublicKey(String publicKeyHex) {
        return publicKeyHex != null && PUBLIC_HEX.matcher(publicKeyHex).matches();
    }

    /** 公钥指纹前 16 位，作为 keyId 随报文下发，对端按 ID 选公钥 */
    public static String keyId(String publicKeyHex) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(publicKeyHex.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String pointToHex(ECPoint point) {
        return POINT_PREFIX
                + leftPad64(point.getAffineXCoord().toBigInteger().toString(16))
                + leftPad64(point.getAffineYCoord().toBigInteger().toString(16));
    }

    private static ECDomainParameters sm2Domain() {
        X9ECParameters params = GMNamedCurves.getByName("sm2p256v1");
        return new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }

    private static String leftPad64(String hex) {
        if (hex.length() >= 64) {
            return hex;
        }
        return "0".repeat(64 - hex.length()) + hex;
    }

    public static void main(String[] args) {
        String[] pair = (args.length > 0)
                ? new String[]{args[0].trim().toLowerCase(), null}
                : generate();
        if (!isValidPrivateKey(pair[0])) {
            System.out.println("privateKeyHex 非法：需要 64 位十六进制，且 0 < d < n");
            return;
        }
        String pub = pair[1] == null ? derivePublicKey(pair[0]) : pair[1];
        System.out.println("privateKeyHex=" + pair[0]);
        System.out.println("publicKeyHex=" + pub);
        System.out.println("keyId=" + keyId(pub));
    }
}
