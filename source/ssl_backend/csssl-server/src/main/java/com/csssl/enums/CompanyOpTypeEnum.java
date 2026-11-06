package com.csssl.enums;

import lombok.Getter;

/**
 * 公司变更单类型：暂停/恢复/关闭/重置接入密钥走双人复核（发起 → 审批 → 批准即执行）。
 *
 * <p>新建公司已改为直接执行（见 {@code CompanyProvisionService}），{@link #CREATE} 仅保留给历史单据
 * 查询与凭证回执，{@link #isSubmittable()} 为 false，不再接受新发起。</p>
 */
@Getter
public enum CompanyOpTypeEnum {

    CREATE("CREATE", "新建公司", false),
    SUSPEND("SUSPEND", "暂停公司", true),
    RESUME("RESUME", "恢复公司", true),
    CLOSE("CLOSE", "关闭公司", true),
    RESET_CREDENTIAL("RESET_CREDENTIAL", "重置接入密钥", true),
    REASSIGN_HOLDERS("REASSIGN_HOLDERS", "重新指派保管人", true);

    private final String code;
    private final String label;

    /** 是否允许新发起（CREATE 为历史类型，不可再提交） */
    private final boolean submittable;

    CompanyOpTypeEnum(String code, String label, boolean submittable) {
        this.code = code;
        this.label = label;
        this.submittable = submittable;
    }

    /** 是否等于给定库值（忽略首尾空白与大小写） */
    public boolean is(String code) {
        return code != null && this.code.equalsIgnoreCase(code.trim());
    }

    /**
     * 由库值取枚举（忽略大小写/空白），未知返回 null
     */
    public static CompanyOpTypeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (CompanyOpTypeEnum t : values()) {
            if (t.code.equals(c)) {
                return t;
            }
        }
        return null;
    }
}
