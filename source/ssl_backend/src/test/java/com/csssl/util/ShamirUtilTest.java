package com.csssl.util;

import cn.hutool.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shamir(3,5) 门限分持单元测试：往返恢复 / 阈值边界 / 篡改检测 / 非对称份额。
 */
class ShamirUtilTest {

    @Test
    void splitAndRecoverRoundTrip() {
        byte[] secret = "csssl-root-private-key-der-bytes".getBytes(StandardCharsets.UTF_8);
        ShamirUtil.Share[] shares = ShamirUtil.split(secret, 5, 3);
        assertArrayEquals(secret, ShamirUtil.recover(new ShamirUtil.Share[]{shares[0], shares[2], shares[4]}));
        assertArrayEquals(secret, ShamirUtil.recover(new ShamirUtil.Share[]{shares[1], shares[3], shares[0]}));
    }

    @Test
    void anyThreeOfFiveWorks() {
        byte[] secret = new byte[128];
        new SecureRandom().nextBytes(secret);
        ShamirUtil.Share[] shares = ShamirUtil.split(secret, 5, 3);
        assertArrayEquals(secret, ShamirUtil.recover(new ShamirUtil.Share[]{shares[4], shares[0], shares[2]}));
        assertArrayEquals(secret, ShamirUtil.recover(new ShamirUtil.Share[]{shares[3], shares[1], shares[2]}));
    }

    @Test
    void twoSharesNotEnough() {
        byte[] secret = "secret".getBytes(StandardCharsets.UTF_8);
        ShamirUtil.Share[] shares = ShamirUtil.split(secret, 5, 3);
        assertThrows(IllegalArgumentException.class,
                () -> ShamirUtil.recover(new ShamirUtil.Share[]{shares[0], shares[1]}));
    }

    @Test
    void duplicateIndexNotEnough() {
        byte[] secret = "secret".getBytes(StandardCharsets.UTF_8);
        ShamirUtil.Share[] shares = ShamirUtil.split(secret, 5, 3);
        assertThrows(IllegalArgumentException.class,
                () -> ShamirUtil.recover(new ShamirUtil.Share[]{shares[0], shares[0], shares[1]}));
    }

    @Test
    void tamperedShareFailsOrDiffers() {
        byte[] secret = "tamper-check".getBytes(StandardCharsets.UTF_8);
        ShamirUtil.Share[] shares = ShamirUtil.split(secret, 5, 3);
        // 篡改一份的 yHex（翻转首个字节）
        byte[] y = HexUtil.decodeHex(shares[1].yHex());
        y[0] ^= 0x01;
        ShamirUtil.Share tampered = new ShamirUtil.Share(shares[1].index(), shares[1].total(),
                shares[1].threshold(), HexUtil.encodeHexStr(y));
        byte[] recovered = ShamirUtil.recover(new ShamirUtil.Share[]{shares[0], tampered, shares[2]});
        assertNotEquals(HexUtil.encodeHexStr(secret), HexUtil.encodeHexStr(recovered),
                "篡改份额恢复结果应与原秘密不同（交由上层公钥验签拦截）");
    }

    @Test
    void mismatchedParamsRejected() {
        byte[] secret = "mismatch".getBytes(StandardCharsets.UTF_8);
        ShamirUtil.Share[] shares = ShamirUtil.split(secret, 5, 3);
        ShamirUtil.Share bad = new ShamirUtil.Share(9, 5, 3,
                shares[0].yHex()); // 不同 index
        ShamirUtil.Share[] input = Arrays.copyOf(shares, 3);
        input[2] = bad;
        // index=9 超出本组范围但份额语法有效：恢复结果不应等于原秘密（上层校验拦截）
        byte[] recovered = ShamirUtil.recover(input);
        assertNotEquals(HexUtil.encodeHexStr(secret), HexUtil.encodeHexStr(recovered));
    }
}
