package com.csssl.vo;

import lombok.Data;

/**
 * 二级CA 重建申请单的审批人（即该公司 5 名密钥保管人）展示项。
 */
@Data
public class CaSubCaHolderVO {

    private int shareIndex;
    private String holderUsername;
    private String holderRealName;
}
