package com.csssl.vo;

import lombok.Data;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * API 客户端登记/轮换回执：secret 仅在本次响应中返回一次，不落日志、不可再次查询。
 */
@Data
public class ApiClientSecretVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String appId;
    private String companyUscc;
    private String companyName;
    private String clientName;
    private String systemName;
    private String secret;
    private Integer secretVersion;
}
