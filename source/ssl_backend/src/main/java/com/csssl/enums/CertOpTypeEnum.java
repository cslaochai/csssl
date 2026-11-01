package com.csssl.enums;

import lombok.Getter;

/**
 * 证书操作单类型（吊销/挂起/解除挂起/更新/换密钥一律走双人复核：发起 → 审批 → 批准即执行）。
 *
 * <p>操作类型与证书当前状态的匹配规则见 {@code CertOpTicketService#checkStatusForOp}。</p>
 */
@Getter
public enum CertOpTypeEnum {

    REVOKE("REVOKE", "吊销"),
    SUSPEND("SUSPEND", "挂起"),
    UNSUSPEND("UNSUSPEND", "解除挂起"),
    RENEW("RENEW", "证书更新"),
    REKEY("REKEY", "密钥更新");

    private final String code;
    private final String label;

    CertOpTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 是否等于给定库值（忽略首尾空白与大小写） */
    public boolean is(String code) {
        return code != null && this.code.equalsIgnoreCase(code.trim());
    }

    /**
     * 由库值取枚举（忽略大小写/空白），未知返回 null
     */
    public static CertOpTypeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (CertOpTypeEnum t : values()) {
            if (t.code.equals(c)) {
                return t;
            }
        }
        return null;
    }
}
