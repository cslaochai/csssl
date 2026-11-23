package com.csssl.dto;

import lombok.Data;

/** 通用单 ID 请求体（替换 @PathVariable Long id）。 */
@Data
public class IdDTO {
    private Long id;
}
