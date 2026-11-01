package com.csssl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.ApiClient;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部系统 API 客户端。
 */
@Mapper
public interface ApiClientMapper extends BaseMapper<ApiClient> {
}
