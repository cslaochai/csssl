package com.csssl.dto;

import lombok.Data;

import java.util.List;

/**
 * 二级CA 重建执行请求：提交 ≥3 份根私钥份额 JSON 加载根私钥后重建二级CA。
 */
@Data
public class SubCaRebuildExecuteDTO {

    /**
     * 根私钥份额 JSON 列表（至少 3 份，编号不同）
     */
    private List<String> shareJsons;
}
