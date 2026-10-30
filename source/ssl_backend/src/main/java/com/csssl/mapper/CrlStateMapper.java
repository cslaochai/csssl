package com.csssl.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.CaOrg;
import com.csssl.entity.CrlState;
import org.apache.ibatis.annotations.Mapper;

/**
 * CRL 发布状态行（路线A：每机构一行，org_key 逻辑唯一）。
 * 行锁按机构读：SELECT ... FOR UPDATE ... LIMIT 1——未建 org_key 唯一索引前（见待加DDL），
 * 取最新一行（id 最大）兜住历史迁移产生的过渡态重复行。
 */
@Mapper
public interface CrlStateMapper extends BaseMapper<CrlState> {

    /**
     * 行锁读取主机构发布状态（兼容入口）
     */
    default CrlState selectForUpdate() {
        return selectForUpdate(CaOrg.PRIMARY_ORG_KEY);
    }

    /**
     * 行锁读取指定机构发布状态行：并发/多实例发布串行化（须在事务内调用才有意义）
     */
    default CrlState selectForUpdate(String orgKey) {
        return selectOne(new LambdaQueryWrapper<CrlState>()
                .eq(CrlState::getOrgKey, orgKey)
                .orderByDesc(CrlState::getId)
                .last("LIMIT 1 FOR UPDATE"));
    }

    /**
     * 读取指定机构发布状态行（只读，不加锁；无行返回 null）
     */
    default CrlState selectByOrg(String orgKey) {
        return selectOne(new LambdaQueryWrapper<CrlState>()
                .eq(CrlState::getOrgKey, orgKey)
                .orderByDesc(CrlState::getId)
                .last("LIMIT 1"));
    }

    /**
     * 当前最大行ID（ssl_crl_state.id 无 AUTO_INCREMENT，新机构行按 max+1 生成）
     */
    default CrlState selectTopByIdDesc() {
        return selectOne(new LambdaQueryWrapper<CrlState>()
                .orderByDesc(CrlState::getId)
                .last("LIMIT 1"));
    }
}
