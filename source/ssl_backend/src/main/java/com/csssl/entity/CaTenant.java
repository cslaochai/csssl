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
 * CA 租户注册表：每家医院=一套独立根CA域（根CA+SUB_CA+签发目录+主密钥+门限份额），租户间互不信任。
 * 平台只有一家在营租户时，它即主租户（{@link #PRIMARY_TENANT_KEY}）；存量单根的落盘路径命名空间
 * 仍是 {@link #LEGACY_TENANT_KEY}，两者刻意分开，避免主租户切换后找不到旧密钥目录。
 */
@Data
@TableName("ssl_ca_tenant")
public class CaTenant {

    /**
     * 存量单根的密钥目录/DN 命名空间（路径兼容判断专用，不随主租户变化）
     */
    public static final String LEGACY_TENANT_KEY = "default";
    /**
     * 平台主租户标识：所有不带租户参数的兼容入口都落到它。
     * 缺省 default（存量单根部署）；启动时由 TenantScope 校准为唯一在营租户。
     */
    public static volatile String PRIMARY_TENANT_KEY = "default";


    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 租户标识（小写字母数字，如 his1）
     */
    private String tenantKey;

    /**
     * 租户名称（展示与新根CA CN用）
     */
    private String tenantName;

    /**
     * 证书主体O（租户受控，根/子CA DN 的 O 字段，全局唯一）
     */
    private String subjectO;

    /**
     * 根CA算法：RSA2048 / SM2（建租户时定，SUB_CA 跟随根）
     */
    private String algorithm;

    /**
     * 状态：ACTIVE-启用 SUSPENDED-暂停 CLOSED-关闭
     */
    private String status;

    /**
     * 联系人/联系方式
     */
    private String contact;

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
