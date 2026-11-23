package com.csssl.vo;

import lombok.Data;

/**
 * 二级CA 重建申请单的审批人（即该公司 5 名密钥保管人）展示项。
 *
 * <p>只出真实姓名，不出登录账号：保管人名单对多个管理角色可见，账号属于可登录凭据的一半，不下发。
 */
@Data
public class CaSubCaHolderVO {

    private int shareIndex;
    private String holderRealName;
}
