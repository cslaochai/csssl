package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * Shamir(3,5) 门限份额状态：5 份各自存在性 + 阈值（根CA管理页）。
 */
@Data
public class RootSharesVO {

    /**
     * 5 份份额是否全部生成
     */
    private boolean generated;

    /**
     * 份额总数
     */
    private int total;

    /**
     * 恢复所需阈值
     */
    private int threshold;

    /**
     * 各份额状态
     */
    private List<ShareItem> shares;

    /**
     * 单份份额存在性
     */
    @Data
    public static class ShareItem {

        /**
         * 份额编号（1~5）
         */
        private int index;

        /**
         * 份额文件是否已生成
         */
        private boolean exists;
    }
}
