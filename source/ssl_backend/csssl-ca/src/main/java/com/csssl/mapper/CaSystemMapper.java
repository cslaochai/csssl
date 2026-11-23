package com.csssl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.CaSystem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公司下的系统（证书域单元）。
 */
@Mapper
public interface CaSystemMapper extends BaseMapper<CaSystem> {
}
