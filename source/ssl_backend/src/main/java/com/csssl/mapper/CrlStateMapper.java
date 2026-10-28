package com.csssl.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.CrlState;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrlStateMapper extends BaseMapper<CrlState> {

    /** 行锁读取单行发布状态：并发/多实例发布串行化（须在事务内调用才有意义） */
    default CrlState selectForUpdate() {
        return selectOne(new LambdaQueryWrapper<CrlState>()
                .eq(CrlState::getId, CrlState.SINGLE_ROW_ID)
                .last("FOR UPDATE"));
    }
}
