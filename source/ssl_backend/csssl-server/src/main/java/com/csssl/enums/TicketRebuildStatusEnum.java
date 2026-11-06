package com.csssl.enums;

import lombok.Getter;

/**
 * 二级CA 重建申请单状态 / 审批决定枚举。
 *
 * <p>状态：PENDING-待审批 APPROVED-已批准 REJECTED-已驳回 CANCELLED-已撤销 EXECUTED-已执行。
 * 决定：APPROVED-同意 REJECTED-驳回。</p>
 */
@Getter
public enum TicketRebuildStatusEnum {

    PENDING("PENDING", "待审批", false),
    APPROVED("APPROVED", "已批准", false),
    REJECTED("REJECTED", "已驳回", false),
    CANCELLED("CANCELLED", "已撤销", false),
    EXECUTED("EXECUTED", "已执行", false),

    DECISION_APPROVED("APPROVED", "同意", true),
    DECISION_REJECTED("REJECTED", "驳回", true);

    private final String code;
    private final String label;
    /** 是否为审批决定值（APPROVED/REJECTED 两类共用 code） */
    private final boolean decision;

    TicketRebuildStatusEnum(String code, String label, boolean decision) {
        this.code = code;
        this.label = label;
        this.decision = decision;
    }

    public boolean is(String code) {
        return code != null && this.code.equalsIgnoreCase(code.trim());
    }

    public static TicketRebuildStatusEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        for (TicketRebuildStatusEnum t : values()) {
            if (!t.decision && t.code.equals(c)) {
                return t;
            }
        }
        return null;
    }
}
