package com.csssl.vo;

import lombok.Data;

/**
 * 单份根密钥门限份额内容（下载导出用，与份额落盘文件字段一致）。
 */
@Data
public class RootShareVO {

    /**
     * 份额编号（1~5）
     */
    private int index;

    /**
     * 份额总数
     */
    private int total;

    /**
     * 恢复所需阈值
     */
    private int threshold;

    /**
     * 份额 y 值（十六进制）
     */
    private String yHex;
}
