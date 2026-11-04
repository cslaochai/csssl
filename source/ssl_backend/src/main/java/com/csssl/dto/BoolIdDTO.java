package com.csssl.dto;

import lombok.Data;

/** 通用：ID + 布尔开关（ApiClientAdminController 启停用）。 */
@Data
public class BoolIdDTO {
    private Long id;
    private Boolean enabled;
}
