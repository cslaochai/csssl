package com.csssl.dto;

import lombok.Data;

/** 通用：编码查询（角色编码 / 流程编码 / 公司 USCC 均可复用）。 */
@Data
public class CodeDTO {
    private String code;
    /** 流程编码（与 code 二选一，flowCode 语义更明确时使用） */
    private String flowCode;
    /** 角色编码（与 code 二选一，roleCode 语义更明确时使用） */
    private String roleCode;

    /** 统一取编码的方法：优先取语义化字段，fallback 到 code */
    public String any() {
        if (roleCode != null && !roleCode.isBlank()) return roleCode;
        if (flowCode != null && !flowCode.isBlank()) return flowCode;
        return code;
    }
}
