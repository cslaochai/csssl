package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 通用：份额编号（1~5）+ 公司域（替换 @PathVariable int index + @RequestParam companyUscc）。 */
@Data
public class ShareDownloadDTO {
    private Integer index;
    @NotBlank(message = "参数 companyUscc 不能为空（公司域）")
    private String companyUscc;
}
