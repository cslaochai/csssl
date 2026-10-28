package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CA 机构注册表：每家医院=一套独立根CA域（根CA+SUB_CA+签发目录+主密钥+门限份额），机构间互不信任。
 * default 机构=存量单根（subject_o='CSSSL'），落盘路径沿用旧根路径保证老对接零破坏。
 */
@Data
@TableName("ssl_ca_org")
public class CaOrg {

    /** 院内默认域机构标识（存量单根兼容位） */
    public static final String DEFAULT_ORG_KEY = "default";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构标识（小写字母数字，如 his1；default=院内默认域） */
    private String orgKey;

    /** 机构名称（展示与新根CA CN用） */
    private String orgName;

    /** 证书主体O（机构受控，根/子CA DN 的 O 字段，全局唯一） */
    private String subjectO;

    /** 根CA算法：RSA2048 / SM2（建机构时定，SUB_CA 跟随根） */
    private String algorithm;

    /** 状态：ACTIVE-启用 SUSPENDED-暂停 CLOSED-关闭 */
    private String status;

    /** 联系人/联系方式 */
    private String contact;

    /** 备注 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
