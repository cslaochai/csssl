package com.csssl.vo;

import lombok.Data;

/**
 * 新建机构回执视图对象：机构标识 + 该机构根CA状态摘要（原 Map.of 两键结构）。
 */
@Data
public class OrgCreateVO {

    private String orgKey;
    private OrgVO rootStatus;
}
