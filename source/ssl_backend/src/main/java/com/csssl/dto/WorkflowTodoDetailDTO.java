package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 待办详情弹窗请求（按业务类型 + 单据 id 取一份完整上下文）。
 */
@Data
public class WorkflowTodoDetailDTO {

    /**
     * 业务类型：CERT_APPLICATION / CERT_OP / COMPANY_OP / SUB_CA_REBUILD
     */
    @NotBlank(message = "参数 bizType 不能为空")
    private String bizType;

    /**
     * 单据 id（待办条目里的 bizId）
     */
    @NotNull(message = "参数 bizId 不能为空")
    private Long bizId;
}
