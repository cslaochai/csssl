package com.csssl.vo;

import lombok.Data;

/**
 * 公司变更单接入凭证回执：secret 仅在本次响应返回一次（发起人本人领取），不落库、不落日志。
 */
@Data
public class CompanyOpReceiptVO {

    private String opNo;
    private String opType;
    private String companyUscc;
    private String companyName;
    private String appId;
    private String secret;
    private Integer secretVersion;
}
