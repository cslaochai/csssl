package com.csssl.enums;

import lombok.Getter;

/**
 * 证书申请单状态（录入 → 审核通过/驳回 → 签发，未签发前可撤销）。
 *
 * <p>库表按 {@link #getCode()} 存字符串；状态迁移见 {@code CertApplicationService}。</p>
 */
@Getter
public enum CertApplicationStatusEnum {

    PENDING("PENDING", "待审核"),
    APPROVED("APPROVED", "已批准"),
    REJECTED("REJECTED", "已驳回"),
    ISSUED("ISSUED", "已签发"),
    CANCELLED("CANCELLED", "已撤销");

    private final String code;
    private final String label;

    CertApplicationStatusEnum(String code, String label) {
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
    public static CertApplicationStatusEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (CertApplicationStatusEnum s : values()) {
            if (s.code.equals(c)) {
                return s;
            }
        }
        return null;
    }
}
