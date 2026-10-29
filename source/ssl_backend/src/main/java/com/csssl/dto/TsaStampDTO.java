package com.csssl.dto;

import lombok.Data;

/** 时间戳盖章请求：被盖时间戳的内容摘要（64 位十六进制） */
@Data
public class TsaStampDTO {

    /** SHA-256/SM3 内容摘要（64 位十六进制） */
    private String digestHex;
}
