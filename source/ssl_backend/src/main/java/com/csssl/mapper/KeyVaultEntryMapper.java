package com.csssl.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.KeyVaultEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 密钥保险库条目（每租户域一个逻辑分区，tenant_key+rel_dir+name 唯一）。
 */
@Mapper
public interface KeyVaultEntryMapper extends BaseMapper<KeyVaultEntry> {

    default KeyVaultEntry selectEntry(String tenantKey, String relDir, String name) {
        return selectOne(new LambdaQueryWrapper<KeyVaultEntry>()
                .eq(KeyVaultEntry::getTenantKey, tenantKey)
                .eq(KeyVaultEntry::getRelDir, relDir)
                .eq(KeyVaultEntry::getName, name));
    }
}
