package com.csssl.dto;

import lombok.Data;

/** 通用：份额编号（1~5）+ 公司域（替换 @PathVariable int index + @RequestParam companyUscc）。 */
@Data
public class ShareDownloadDTO {
    private Integer index;
    private String companyUscc;
}
