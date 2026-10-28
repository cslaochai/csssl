package com.csssl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csssl.entity.CertApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 证书申请单 Mapper（RA 流程，GM/T 0034 §5.2.2）。
 */
@Mapper
public interface CertApplicationMapper extends BaseMapper<CertApplication> {

    /**
     * 悲观行锁读取申请单（须在事务内调用）：签发并发双签的根因是
     * 「读状态→签发→置 ISSUED」非原子，两个操作员同时通过状态检查即各签一张证书。
     */
    @Select("SELECT * FROM ssl_cert_application WHERE id = #{id} FOR UPDATE")
    CertApplication selectForUpdate(@Param("id") Long id);
}
