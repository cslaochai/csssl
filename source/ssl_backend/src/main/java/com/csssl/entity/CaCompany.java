package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CA 公司注册表：每家医院=一套独立根CA域（根CA+SUB_CA+签发目录+主密钥+门限份额），公司间互不信任。
 * 统一社会信用代码即公司标识（路径/接口/落盘目录同名），不另设内部标识字段。
 * 平台只有一家在营公司时，它即主公司（{@link #PRIMARY_COMPANY_USCC}）；存量单根的落盘路径命名空间
 * 仍是 {@link #LEGACY_NAMESPACE}，两者刻意分开，避免主公司切换后找不到旧密钥目录。
 */
@Data
@TableName("ssl_ca_company")
public class CaCompany {

    /**
     * 存量单根的密钥目录/DN 命名空间（路径兼容判断专用，不随主公司变化）
     */
    public static final String LEGACY_NAMESPACE = "default";
    /**
     * 平台主公司标识：所有不带公司参数的兼容入口都落到它。
     * 缺省 default（存量单根部署）；启动时由 CompanyScope 校准为唯一在营公司。
     */
    public static volatile String PRIMARY_COMPANY_USCC = "default";


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

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
