package com.csssl.vo;

import lombok.Data;

/**
 * 证书概览统计：仪表盘渲染用，key 与原 Map 口径完全一致。
 */
@Data
public class CertOverviewVO {

    /**
     * 证书总量
     */
    private long total;

    /**
     * 有效证书数
     */
    private long valid;

    /**
     * 已吊销证书数
     */
    private long revoked;

    /**
     * 已过期证书数
     */
    private long expired;

    /**
     * SERVER 类型数量
     */
    private long server;

    /**
     * CLIENT 类型数量
     */
    private long client;

    /**
     * SUB_CA 类型数量
     */
    private long subCa;

    /**
     * 30天内到期数量
     */
    private long expiring30d;

    /**
     * 根CA状态（概览卡渲染）
     */
    private CaStatusVO root;
}
