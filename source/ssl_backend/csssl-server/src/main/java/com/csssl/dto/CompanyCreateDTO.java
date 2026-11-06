package com.csssl.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建公司请求（持 company:create，直接执行：建公司即建独立根CA+SUB_CA 并发放默认接入凭证，无需双人复核）。
 */
@Data
public class CompanyCreateDTO {

    /**
     * 统一社会信用代码（GB 32100，18 位；即公司标识）
     */
    private String uscc;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 系统名称（即证书主体 O）：建公司时同时创建该公司首个系统，公司内系统不重名
     */
    private String subjectO;

    /**
     * 算法（可空，缺省取全局配置）
     */
    private String algorithm;

    /**
     * 联系人（可空）
     */
    private String contact;

    /**
     * 联系方式（可空，11位手机号）
     */
    private String contactPhone;

    /**
     * 说明（可空）
     */
    private String description;

    /**
     * 密钥份额保管人（建根时指定恰好 5 名，一人一份，落表绑定；用于后续动用根私钥需 3-of-5 审批）
     */
    @NotEmpty(message = "必须指定密钥保管人")
    @Size(min = 5, max = 5, message = "必须指定恰好 5 名密钥保管人")
    private java.util.List<String> shareHolders;
}
