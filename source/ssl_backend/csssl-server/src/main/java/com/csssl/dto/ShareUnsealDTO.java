package com.csssl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 根私钥门限份额解封请求：提交任意 3 份份额 JSON 重组根私钥（仅内存，用后封存）。
 */
@Data
public class ShareUnsealDTO {

    /**
     * 公司域（必须显式指定）
     */
    @NotBlank(message = "参数 companyUscc 不能为空（公司域）")
    private String companyUscc;

    /**
     * 份额 JSON 列表（至少 3 份，编号不同）
     */
    private List<String> shares;
}
