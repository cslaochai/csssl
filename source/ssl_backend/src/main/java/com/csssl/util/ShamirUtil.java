package com.csssl.util;

import cn.hutool.core.util.HexUtil;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Shamir 秘密共享（GF(256) 有限域，模多项式 x^8+x^4+x^3+x+1，即 0x11B）。
 *
 * <p>用途：根CA私钥门限分持——把根私钥 DER 拆成 N=5 份份额，任取 K=3 份即可恢复，
 * 任何少于 3 份的持有者都无法得到私钥（GM/T 0034 §8.2.4.3 门限控制、密评"根密钥不得单点持有"）。
 * 与通用 Shamir 实现一致：秘密作为多项式常数项，份额为多项式在 x=1..N 上的取值。</p>
 *
 * <p>字节级实现：秘密每个字节独立建多项式（长度无限制），份额 yHex = 各字节 y 值拼接（每字节 2 hex）。</p>
 */
public final class ShamirUtil {

    private static final int GF = 0x11B;
    private static final byte[] LOG = new byte[256];
    private static final byte[] EXP = new byte[512];

    static {
        // 注意：GF(2^8)/0x11B 中 2 不是本原元（阶=204 < 255，覆盖不全），须显式求一个阶 255 的本原元建表
        byte gen = findPrimitive();
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = (byte) x;
            LOG[x & 0xFF] = (byte) i;
            x = gfMulSlow((byte) x, gen);
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    /** 朴素多项式乘法（不依赖对数表，仅用于建表前求本原元） */
    private static byte gfMulSlow(byte a, byte b) {
        int r = 0;
        int p = a & 0xFF;
        int q = b & 0xFF;
        for (int i = 0; i < 8; i++) {
            if ((q & 1) != 0) {
                r ^= p;
            }
            q >>= 1;
            p <<= 1;
            if ((p & 0x100) != 0) {
                p ^= GF;
            }
        }
        return (byte) r;
    }

    /** 求阶为 255 的本原元（乘法群生成元；2 阶为 204 非本原，从 3 起试） */
    private static byte findPrimitive() {
        for (int g = 2; g < 256; g++) {
            byte v = (byte) 1;
            for (int order = 1; order <= 255; order++) {
                v = gfMulSlow(v, (byte) g);
                if ((v & 0xFF) == 1) {
                    if (order == 255) {
                        return (byte) g;
                    }
                    break; // 阶 < 255，非本原
                }
            }
        }
        throw new IllegalStateException("GF(2^8)/0x11B 本原元查找失败");
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private ShamirUtil() {
    }

    /** 一份门限份额（JSON 序列化字段） */
    public record Share(int index, int total, int threshold, String yHex) {
    }

    /**
     * 拆分秘密：生成 total 份份额，任意 threshold 份可恢复（threshold ≤ total ≤ 255）。
     *
     * @param secret 秘密字节（如根私钥 PKCS#8 DER）
     */
    public static Share[] split(byte[] secret, int total, int threshold) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("秘密不能为空");
        }
        if (threshold < 2 || threshold > total || total > 255) {
            throw new IllegalArgumentException("门限参数无效：2 ≤ threshold ≤ total ≤ 255");
        }
        Share[] shares = new Share[total];
        byte[][] yValues = new byte[total][secret.length];
        for (int i = 0; i < secret.length; i++) {
            byte secretByte = secret[i];
            byte[] coeff = new byte[threshold];
            coeff[0] = secretByte;
            for (int k = 1; k < threshold; k++) {
                byte[] b = new byte[1];
                RANDOM.nextBytes(b);
                coeff[k] = b[0];
            }
            for (int x = 1; x <= total; x++) {
                yValues[x - 1][i] = eval(coeff, (byte) x);
            }
        }
        for (int i = 0; i < total; i++) {
            shares[i] = new Share(i + 1, total, threshold, HexUtil.encodeHexStr(yValues[i]));
        }
        return shares;
    }

    /**
     * 恢复秘密：提交至少 threshold 份不同 index 的份额。
     *
     * @param shareList 份额列表（至少 threshold 份）
     * @return 恢复出的秘密字节
     */
    public static byte[] recover(Share[] shareList) {
        if (shareList == null || shareList.length < 2) {
            throw new IllegalArgumentException("份额数量不足");
        }
        Share first = shareList[0];
        int threshold = first.threshold();
        if (shareList.length < threshold) {
            throw new IllegalArgumentException("份额不足：需要至少 " + threshold + " 份（当前 " + shareList.length + "）");
        }
        int secretLen = HexUtil.decodeHex(first.yHex()).length;
        for (Share s : shareList) {
            if (s.threshold() != threshold || HexUtil.decodeHex(s.yHex()).length != secretLen) {
                throw new IllegalArgumentException("份额参数不一致（threshold/长度），请核对份额来源");
            }
        }
        int[] xs = Arrays.stream(shareList).mapToInt(Share::index).distinct().toArray();
        if (xs.length < threshold) {
            throw new IllegalArgumentException("存在重复份额编号，至少需要 " + threshold + " 个不同编号");
        }
        byte[][] ys = Arrays.stream(shareList)
                .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                .map(s -> HexUtil.decodeHex(s.yHex()))
                .toArray(byte[][]::new);
        int[] sortedXs = Arrays.stream(shareList)
                .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                .mapToInt(Share::index).toArray();

        byte[] secret = new byte[secretLen];
        for (int i = 0; i < secretLen; i++) {
            secret[i] = interpolateZero(sortedXs, ys, i, threshold);
        }
        return secret;
    }

    /** 多项式求值 f(x) = c0 + c1*x + ... + c_{k-1}*x^{k-1}（GF(256)） */
    private static byte eval(byte[] coeff, byte x) {
        byte result = 0;
        byte xPow = 1;
        for (byte c : coeff) {
            result = gfAdd(result, gfMul(c, xPow));
            xPow = gfMul(xPow, x);
        }
        return result;
    }

    /** Lagrange 插值求 f(0) */
    private static byte interpolateZero(int[] xs, byte[][] ys, int col, int threshold) {
        byte result = 0;
        for (int j = 0; j < threshold; j++) {
            byte basis = 1;
            for (int m = 0; m < threshold; m++) {
                if (m == j) {
                    continue;
                }
                // l_j(0) = ∏_{m≠j} x_m / (x_m - x_j)
                byte xj = (byte) xs[j];
                byte xm = (byte) xs[m];
                basis = gfMul(basis, gfDiv(xm, gfAdd(xm, xj)));
            }
            result = gfAdd(result, gfMul(ys[j][col], basis));
        }
        return result;
    }

    private static byte gfAdd(byte a, byte b) {
        return (byte) (a ^ b);
    }

    private static byte gfMul(byte a, byte b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[(LOG[a & 0xFF] & 0xFF) + (LOG[b & 0xFF] & 0xFF)];
    }

    private static byte gfDiv(byte a, byte b) {
        if (b == 0) {
            throw new ArithmeticException("GF(256) 除零");
        }
        if (a == 0) {
            return 0;
        }
        return EXP[(LOG[a & 0xFF] & 0xFF) - (LOG[b & 0xFF] & 0xFF) + 255];
    }
}
