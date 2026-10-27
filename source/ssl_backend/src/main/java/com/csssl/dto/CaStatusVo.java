package com.csssl.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 根CA状态：概览/根CA管理页渲染用（含 SUB_CA 与根密钥封存状态）。
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

    /** 根私钥状态：ONLINE（在线）/ SEALED（已封存离线）/ SHARES（Shamir(3,5) 门限分持）/ MISSING（缺失） */
    private String rootKeyState;

    /** Shamir(3,5) 门限份额状态：generated / total=5 / threshold=3 / shares=[{index, exists}] */
    private java.util.Map<String, Object> shareState;

    /** 服务签发二级CA（SUB_CA）信息 */
    private SubCaStatus subCa;

    /** 未初始化原因（密钥丢失等） */
    private String message;

    /** SUB_CA 状态 */
    @Data
    public static class SubCaStatus {
        /** SUB_CA证书CN */
        private String cn;
        /** 序列号 */
        private String serialHex;
        /** 算法 */
        private String algorithm;
        /** 生效时间 */
        private LocalDateTime validFrom;
        /** 过期时间 */
        private LocalDateTime validTo;
        /** 状态 */
        private String status;
        /** 到期前多少天自动轮换 */
        private Integer renewBeforeDays;
    }
}
