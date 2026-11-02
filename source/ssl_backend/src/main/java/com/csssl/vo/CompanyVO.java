package com.csssl.vo;

import com.csssl.entity.CaCompany;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公司行视图对象：列表/分页/单公司状态摘要共用（含根CA与SUB_CA状态摘要）。
 * 未取到的可选字段（SUB_CA 明细、statusError）序列化时省略，与原 Map 出参逐 key 对齐。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompanyVO {

    /**
     * 统一社会信用代码：公司唯一标识
     */
    private String uscc;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 证书主体O
     */
    private String subjectO;

    /**
     * 根CA算法：RSA2048 / SM2
     */
    private String algorithm;

    /**
     * 公司状态：ACTIVE / SUSPENDED / CLOSED
     */
    private String status;

    /**
     * 联系人姓名
     */
    private String contact;

    /**
     * 联系人手机号（列表/详情展示做掩码）
     */
    private String contactPhone;

    /**
     * 说明
     */
    private String description;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 是否已初始化根CA
     */
    private boolean hasRoot;

    /**
     * 根CA序列号
     */
    private String rootSerialHex;

    /**
     * 根CA证书CN
     */
    private String rootCn;

    /**
     * 根CA状态
     */
    private String rootStatus;

    /**
     * 根CA生效时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rootValidFrom;

    /**
     * 根CA过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rootValidTo;

    /**
     * 根私钥状态：ONLINE / SEALED / SHARES / MISSING
     */
    private String rootKeyState;

    /**
     * SUB_CA 序列号
     */
    private String subCaSerialHex;

    /**
     * SUB_CA 状态
     */
    private String subCaStatus;

    /**
     * SUB_CA 生效时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime subCaValidFrom;

    /**
     * SUB_CA 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime subCaValidTo;

    /**
     * 单公司状态读取异常原因（如密钥文件损坏；不拖垮整个列表）
     */
    private String statusError;

    /**
     * 该公司默认接入客户端的记录 ID（无则 null）
     */
    private Long apiClientId;

    /**
     * 接入 ID（App ID）：外部系统配置后随请求头 X-App-Id 发送，CA 据此反查公司
     */
    private String apiAppId;

    /**
     * 接入状态：1-启用 0-停用（无接入凭证时省略）
     */
    private Integer apiClientStatus;

    /**
     * 公司档案部分（根CA状态字段由服务层按公司实时状态填充）
     */
    public static CompanyVO from(CaCompany company) {
        CompanyVO vo = new CompanyVO();
        vo.setUscc(company.getUscc());
        vo.setCompanyName(company.getCompanyName());
        vo.setSubjectO(company.getSubjectO());
        vo.setAlgorithm(company.getAlgorithm());
        vo.setStatus(company.getStatus());
        vo.setContact(company.getContact());
        vo.setContactPhone(company.getContactPhone());
        vo.setDescription(company.getDescription());
        vo.setCreatedAt(company.getCreatedAt());
        return vo;
    }
}
