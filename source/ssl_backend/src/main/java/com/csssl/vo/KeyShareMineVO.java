package com.csssl.vo;

import lombok.Data;

/**
 * 当前登录人自身持有的根私钥份额（仅保管人本人可见，不可见他人份额）。
 */
@Data
public class KeyShareMineVO {

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

    /**
     * 保管人真实姓名
     */
    private String holderRealName;

    /**
     * 份额完整 JSON（用于复制后粘贴到"加载根私钥"输入框；含 index/total/threshold/yHex）
     */
    private String shareJson;
}
