package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 证书操作单提交请求（吊销/挂起/解除挂起/更新/重签；提交后生成待审批操作单，不直接执行）。
 */
@Data
public class CertOpSubmitDTO {

    /**
     * 操作类型：REVOKE-吊销 SUSPEND-挂起 UNSUSPEND-解除挂起 RENEW-证书更新 REKEY-密钥更新
     */
    private String opType;

    /**
     * 目标证书ID
     */
    private Long certId;

    /**
     * 操作原因（原因码，如 KEY_COMPROMISE；吊销/挂起必填，其余可空）
     */
    private String reason;

    /**
     * 新有效期天数（RENEW/REKEY 必填）
     */
    private Integer validityDays;

    /**
     * 提交时指定的审批人（用户ID，单选）：必须来自流程首个审批节点权限标识筛选出的候选人。
     */
    private Long assigneeUserId;

    /**
     * 公司数据域（必须显式指定）
     */
    @NotBlank(message = "参数 companyUscc 不能为空（公司域）")
    private String companyUscc;
}
