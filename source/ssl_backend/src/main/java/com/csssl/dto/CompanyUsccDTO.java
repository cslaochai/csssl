package com.csssl.dto;

import lombok.Data;

/** 通用：仅公司域（替换 @RequestParam companyUscc）。 */
@Data
public class CompanyUsccDTO {
    private String companyUscc;
}
