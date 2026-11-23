package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公司下的「系统」：真正的证书域单元。
 *
 * <p>模型：公司（客户主体，可理解为一家医院）只是档案壳；系统才是产品单元，
 * 每个系统对应一张专属二级 CA、一套接入凭证与独立的 CRL/吊销域。
 * 系统名称即证书主体 O，公司内唯一。根 CA 属于公司，不属于任何系统。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_ca_system")
public class CaSystem extends AuditColumns {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 所属公司（统一社会信用代码）
     */
    private String companyUscc;

    /**
     * 系统名称（= 证书主体 O；公司内唯一，禁止路径分隔符）
     */
    private String systemName;

    /**
     * 1-启用 0-停用
     */
    private Integer status;

    /**
     * 说明（可选）
     */
    private String description;
}
