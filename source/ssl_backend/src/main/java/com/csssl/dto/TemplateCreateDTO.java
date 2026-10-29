package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建证书模板请求（orgKey 空=全局模板；内置字段由服务端固化）。
 */
@Data
public class TemplateCreateDTO {

    /** 模板归属机构（空=全局模板；机构操作者由服务端锁本机构） */
    private String orgKey;

    /** 模板编码 */
    @NotBlank(message = "参数 templateCode 不能为空")
    private String templateCode;

    /** 模板名称 */
    @NotBlank(message = "参数 templateName 不能为空")
    private String templateName;

    /** 证书类型（SERVER/CLIENT/SUB_CA） */
    @NotBlank(message = "参数 certType 不能为空")
    private String certType;

    /** 算法（缺省 SM2） */
    private String algorithm;

    /** 有效期天数（缺省 365，1~36500） */
    private Integer validityDays;

    /** KeyUsage（逗号分隔） */
    @NotBlank(message = "证书模板必须配置 KeyUsage")
    private String keyUsage;

    /** 扩展密钥用途 EKU（可选） */
    private String extKeyUsage;

    /** 策略 OID（可选） */
    private String policyOid;

    /** 说明 */
    private String description;
}
