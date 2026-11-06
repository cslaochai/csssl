package com.csssl.dto;

import lombok.Data;

/** 通用：仅公司域（替换 @RequestParam companyUscc）。 */
@Data
public class CompanyUsccDTO {
    private String companyUscc;
    /** 系统维度（证书主体 O）：缺省时回落到该公司首个系统名 */
    private String systemName;
}
