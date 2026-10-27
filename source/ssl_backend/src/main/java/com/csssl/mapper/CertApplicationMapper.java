package com.csssl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.CertApplication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 证书申请单 Mapper（RA 流程，GM/T 0034 §5.2.2）。
 */
@Mapper
public interface CertApplicationMapper extends BaseMapper<CertApplication> {
}
