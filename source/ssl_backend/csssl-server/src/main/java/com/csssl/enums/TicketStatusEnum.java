package com.csssl.enums;

import lombok.Getter;

/**
 * 审批单状态（公司变更单 / 证书操作单共用同一状态机：PENDING → EXECUTED / REJECTED / CANCELLED）。
 *
 * <p>枚举是状态码与中文名的单一来源：库表按 {@link #getCode()} 存字符串，
 * 业务判定用 {@link #is(String)}，出参与审计文案用 {@link #getLabel()}。</p>
 */
@Getter
public enum TicketStatusEnum {

    PENDING("PENDING", "待审批"),
    EXECUTED("EXECUTED", "已执行"),
    REJECTED("REJECTED", "已驳回"),
    CANCELLED("CANCELLED", "已撤销");

    private final String code;
    private final String label;

    TicketStatusEnum(String code, String label) {
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
    public static TicketStatusEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (TicketStatusEnum s : values()) {
            if (s.code.equals(c)) {
                return s;
            }
        }
        return null;
    }
}
