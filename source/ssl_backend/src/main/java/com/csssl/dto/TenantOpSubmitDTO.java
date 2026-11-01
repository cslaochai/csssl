package com.csssl.dto;

import lombok.Data;

/**
 * 租户生命周期变更单提交请求（暂停/恢复/关闭/重置接入密钥；提交后仅落待审批单，不直接执行）。
 * 新建租户不在此列：已改为直接执行（见 TenantCreateDTO / POST /admin/tenants）。
 */
@Data
public class TenantOpSubmitDTO {

    /**
     * 变更类型：SUSPEND-暂停 RESUME-恢复 CLOSE-关闭 RESET_CREDENTIAL-重置接入密钥
     */
    private String opType;

    /**
     * 目标租户统一社会信用代码（必填）
     */
    private String uscc;

    /**
     * 变更事由（审批依据，必填）
     */
    private String reason;
}
