package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * CA 公司注册表：每家医院=一套独立根CA域（根CA+SUB_CA+签发目录+主密钥+门限份额），公司间互不信任。
 * 统一社会信用代码即公司标识（路径/接口/落盘目录同名），不另设内部标识字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_ca_company")
public class CaCompany extends AuditColumns {

    /**
     * 存量单根的密钥目录/DN 命名空间（路径兼容判断专用，历史语义空=默认域）
     */
    public static final String LEGACY_NAMESPACE = "default";


    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 统一社会信用代码（18位，GB 32100）：公司唯一标识（路径/接口用）+ 登记唯一键
     */
    private String uscc;

    /**
     * 公司名称（展示与新根CA CN用）
     */
    private String companyName;

    /**
     * 证书主体O（公司受控，根/子CA DN 的 O 字段，全局唯一）
     */
    private String subjectO;

    /**
     * 根CA算法：RSA2048 / SM2（建公司时定，SUB_CA 跟随根）
     */
    private String algorithm;

    /**
     * 状态：ACTIVE-启用 SUSPENDED-暂停 CLOSED-关闭
     */
    private String status;

    /**
     * 联系人姓名
     */
    private String contact;

    /**
     * 联系人手机号（前端展示做掩码，用于CA运维联络）
     */
    private String contactPhone;

    /**
     * 备注
     */
    private String description;
}
