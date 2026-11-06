package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增系统请求（公司内系统名唯一）。
 */
@Data
public class CaSystemCreateDTO {

    @NotBlank(message = "必须指定所属公司")
    private String companyUscc;

    /**
     * 系统名称（= 证书主体 O；公司内唯一）
     */
    @NotBlank(message = "系统名称不能为空")
    private String systemName;

    private String description;
}
