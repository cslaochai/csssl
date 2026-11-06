package com.csssl.dto;

import lombok.Data;

/**
 * 公司生命周期变更单提交请求（暂停/恢复/关闭/重置接入密钥；提交后仅落待审批单，不直接执行）。
 * 新建公司不在此列：已改为直接执行（见 CompanyCreateDTO / POST /admin/companies）。
 */
@Data
public class CompanyOpSubmitDTO {

    /**
     * 变更类型：SUSPEND-暂停 RESUME-恢复 CLOSE-关闭 RESET_CREDENTIAL-重置接入密钥
     */
    private String opType;

    /**
     * 目标公司统一社会信用代码（必填）
     */
    private String uscc;

    /**
     * 变更事由（审批依据，必填）
     */
    private String reason;

    /**
     * 提交时指定的审批人（用户ID，单选）：必须来自流程首个审批节点权限标识筛选出的候选人。
     * REASSIGN_HOLDERS 不走流程，忽略本字段。
     */
    private Long assigneeUserId;

    /**
     * 新密钥保管人账号（仅 REASSIGN_HOLDERS 重新指派保管人专用，须恰好 5 名、互不相同、均为启用的密钥管理员）。
     * 其他变更类型忽略本字段。
     */
    private java.util.List<String> newHolders;
}
