package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 时间戳盖章结果：业务系统（HIS）落台账字段，令牌自证（无状态）。
 */
@Data
public class StampVO {

    /**
     * 令牌序列号（业务侧台账唯一键）
     */
    private String serial;

    /**
     * 授时时刻（秒级）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime tsaTime;

    /**
     * SM3withSM2 签名令牌（Base64）
     */
    private String tokenValue;

    /**
     * 签名算法
     */
    private String algo;

    /**
     * TSA 公钥 SM3 指纹（人工核对用）
     */
    private String publicKeyFingerprint;
}
