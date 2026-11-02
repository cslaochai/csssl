package com.csssl.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.KeyVaultEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 密钥保险库条目（每公司域一个逻辑分区，company_uscc+rel_dir+name 唯一）。
 */
@Mapper
public interface KeyVaultEntryMapper extends BaseMapper<KeyVaultEntry> {

    default KeyVaultEntry selectEntry(String companyUscc, String relDir, String name) {
        LambdaQueryWrapper<KeyVaultEntry> qw = new LambdaQueryWrapper<KeyVaultEntry>()
                .eq(KeyVaultEntry::getRelDir, relDir)
                .eq(KeyVaultEntry::getName, name);
        // NULL=平台级保险库（TSA/审计签名/接入密钥），等值匹配不到 NULL，必须走 IS NULL
        if (companyUscc == null) {
            qw.isNull(KeyVaultEntry::getCompanyUscc);
        } else {
            qw.eq(KeyVaultEntry::getCompanyUscc, companyUscc);
        }
        return selectOne(qw);
    }
}
