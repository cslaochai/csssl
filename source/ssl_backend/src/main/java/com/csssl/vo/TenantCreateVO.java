package com.csssl.vo;

import lombok.Data;

/**
 * 新建租户回执视图对象：租户标识 + 该租户根CA状态摘要（原 Map.of 两键结构）。
 */
@Data
public class TenantCreateVO {

    private String tenantKey;
    private TenantVO rootStatus;
}
