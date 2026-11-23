package com.csssl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.CertTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 证书模板 Mapper（GM/T 0034 §5.3.4）。
 */
@Mapper
public interface CertTemplateMapper extends BaseMapper<CertTemplate> {
}
