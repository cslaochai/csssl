package com.csssl.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 审计记录验签结果视图对象：单条日志防篡改核验（GM/T 0034 §10.1/§10.5）。
 */
@Data
public class AuditVerifyVO {

    /**
     * 审计记录 id（转字符串，防 JS 大数精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 签名是否有效（改动任一字段即失败）
     */
    private boolean valid;

    /**
     * 验签结论描述
     */
    private String message;

    /**
     * 参与签名的规范化串
     */
    private String canonical;

    /**
     * 本条记录签名（缺失时为空串）
     */
    private String sign;

    /**
     * 上一条记录签名（链段，缺失时为空串）
     */
    private String prevSign;
}
