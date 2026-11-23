package com.csssl.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统视图（管理端列表/详情）。
 */
@Data
public class CaSystemVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String companyUscc;

    private String companyName;

    /**
     * 系统名称（= 证书主体 O）
     */
    private String systemName;

    private Integer status;

    private String description;

    private LocalDateTime createdAt;

    public static CaSystemVO from(com.csssl.entity.CaSystem s) {
        CaSystemVO vo = new CaSystemVO();
        vo.setId(s.getId());
        vo.setCompanyUscc(s.getCompanyUscc());
        vo.setSystemName(s.getSystemName());
        vo.setStatus(s.getStatus());
        vo.setDescription(s.getDescription());
        vo.setCreatedAt(s.getCreatedAt());
        return vo;
    }
}
