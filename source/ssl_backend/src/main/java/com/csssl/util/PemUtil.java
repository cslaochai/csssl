package com.csssl.util;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * PEM 编解码工具：证书/私钥的 PEM 文本与对象互转。
 */
public final class PemUtil {

    private PemUtil() {
    }

    /** DER 二进制 → PEM 文本 */
    public static String toPem(String type, byte[] der) {
        StringWriter sw = new StringWriter();
        try (PemWriter pw = new PemWriter(sw)) {
            pw.writeObject(new PemObject(type, der));
        } catch (Exception e) {
            throw new IllegalStateException("PEM 编码失败: " + e.getMessage(), e);
        }
        return sw.toString();
    }

    /** X509Certificate → PEM 文本（CERTIFICATE） */
    public static String certToPem(X509Certificate cert) {
        try {
            return toPem("CERTIFICATE", cert.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("证书编码失败: " + e.getMessage(), e);
        }
    }

    /** X509CRL → PEM 文本（X509 CRL） */
    public static String crlToPem(java.security.cert.X509CRL crl) {
        try {
            return toPem("X509 CRL", crl.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("CRL编码失败: " + e.getMessage(), e);
        }
    }

    /** PrivateKey → PKCS#8 PEM 文本（PRIVATE KEY） */
    public static String keyToPem(PrivateKey key) {
        try {
            return toPem("PRIVATE KEY", key.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("私钥编码失败: " + e.getMessage(), e);
        }
    }

    /** PEM 文本 → X509Certificate（走 BC 解析，支持 SM2 国密曲线证书；JDK 默认 Factory 不认识 sm2p256v1 OID） */
    public static X509Certificate parseCert(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj instanceof X509CertificateHolder holder) {
                return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                        .setProvider("BC").getCertificate(holder);
            }
            throw new IllegalArgumentException("证书PEM解析失败：不支持的格式");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("证书PEM解析失败: " + e.getMessage(), e);
        }
    }

    /** PEM 文本 → 私钥（PKCS#8 / PKCS#1 均支持，走 BC PEMParser；BC provider 支持 SM2 国密私钥 OID） */
    public static PrivateKey parsePrivateKey(String pem) {
        try {
            try (PEMParser parser = new PEMParser(new StringReader(pem))) {
                Object obj = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
                if (obj instanceof PEMKeyPair kp) {
                    return converter.getKeyPair(kp).getPrivate();
                }
                if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo pki) {
                    return converter.getPrivateKey(pki);
                }
            }
            throw new IllegalArgumentException("私钥PEM解析失败：不支持的格式");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("私钥PEM解析失败: " + e.getMessage(), e);
        }
    }

    /** 字节数组 → UTF-8 文本（下载场景） */
    public static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}
