package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 密钥保险库条目：私钥 SM4 加密后的 hex 密文落库（替代原 key.pem.enc 文件形态）。
 * 主密钥（KEK）刻意不入库——环境变量注入或 store-dir/master.key 文件，与密文异存储，
 * 数据库被整库拖走也拿不到明文。
 */
@Data
@TableName("ssl_key_vault")
public class KeyVaultEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户域（default=存量单根保险库；其他=租户独立保险库，主密钥亦独立） */
    private String tenantKey;

    /** 保险库相对目录（root/subca/tsa/audit-sign/issued/{serial}），兼容原文件路径语义 */
    private String relDir;

    /** 密钥名（key/pub） */
    private String name;

    /** SM4 加密后的私钥 PEM（hex 密文） */
    private String cipherHex;

    /** 1=已封存离线（对应原 .sealed 文件态） */
    private Integer sealed;
}
