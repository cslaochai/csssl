package com.csssl.dto;

import lombok.Data;

import java.util.List;

/**
 * 指定根私钥份额保管人请求：建根/开公司时指定 5 名保管人，一人一份（也可用于存量公司后续手动指派）。
 */
@Data
public class ShareAssignDTO {

    /**
     * 公司标识（统一社会信用代码）
     */
    private String companyUscc;

    /**
     * 5 名保管人登录账号（须存在且启用，互不相同）
     */
    private List<String> holderUsernames;
}
