package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 通用：单 ID + 公司域（替换 @PathVariable Long id + @RequestParam companyUscc）。 */
@Data
public class IdCompanyUsccDTO {
    private Long id;
    @NotBlank(message = "参数 companyUscc 不能为空（公司域）")
    private String companyUscc;
}
