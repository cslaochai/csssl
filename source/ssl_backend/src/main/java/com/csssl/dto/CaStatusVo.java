package com.csssl.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 根CA状态：概览/根CA管理页渲染用。
 */
@Data
public class CaStatusVo {

    /** 是否已初始化（存在根证书且私钥可加载） */
    private boolean initialized;

    /** 根CA证书CN */
    private String cn;

    /** 算法：RSA2048 / SM2 */
    private String algorithm;

    /** 序列号 */
    private String serialHex;

    /** 生效时间 */
    private LocalDateTime validFrom;

    /** 过期时间 */
    private LocalDateTime validTo;

    /** 状态 */
    private String status;

    /** 未初始化原因（密钥丢失等） */
    private String message;
}
