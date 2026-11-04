package com.csssl.dto;

import lombok.Data;

/** 通用：单 ID + 公司域（替换 @PathVariable Long id + @RequestParam companyUscc）。 */
@Data
public class IdCompanyUsccDTO {
    private Long id;
    private String companyUscc;
}
