package com.csssl.vo;

import lombok.Data;

/**
 * 新建公司回执：建公司同时发放默认接入凭证，secret 仅在本次响应返回一次，不落库、不落日志。
 */
@Data
public class CompanyCreateVO {

    private String companyUscc;
    private String companyName;
    private String subjectO;

    /**
     * 首个系统名称（与 subjectO 相同；系统才是证书域单元）
     */
    private String systemName;

    private String algorithm;
    private String appId;
    private String secret;
    private Integer secretVersion;

    /**
     * 密钥份额保管人账号（开公司时指定；一人一份，用于后续动用根私钥需 3-of-5 审批）
     */
    private java.util.List<String> shareHolders;
}
