package com.csssl.enums;

import lombok.Getter;

/**
 * 租户状态（生命周期：启用 → 暂停/关闭，两者均可经「申请恢复」变更单回到启用）。
 *
 * <p>库表按 {@link #getCode()} 存字符串；状态迁移约束见 {@code TenantService}。</p>
 */
@Getter
public enum TenantStatusEnum {

    ACTIVE("ACTIVE", "启用"),
    SUSPENDED("SUSPENDED", "已暂停"),
    CLOSED("CLOSED", "已关闭");

    private final String code;
    private final String label;

    TenantStatusEnum(String code, String label) {
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
    public static TenantStatusEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (TenantStatusEnum s : values()) {
            if (s.code.equals(c)) {
                return s;
            }
        }
        return null;
    }
}
