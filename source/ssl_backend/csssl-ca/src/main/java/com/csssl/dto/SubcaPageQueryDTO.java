package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 二级CA重建申请单分页查询（替换 CaSubCaRebuildController#page 的 @RequestParam 五项）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubcaPageQueryDTO extends PageParam {
    private String status;
    private String keyword;
    private String companyUscc;
}
