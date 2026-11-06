package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通用审计列（除工作流程表外的全部业务表都挂它）：创建人/创建人姓名/创建时间 + 修改人/修改人姓名/修改时间。
 *
 * <p>操作人一律是 {@code ssl_sys_user.id}，姓名按登录态快照落库——账号是凭证，不得进业务表；
 * 姓名进业务表，读出来直接展示，不在查询时回查 {@code ssl_sys_user} 现拼。
 * 六个列由 {@link com.csssl.config.AuditMetaObjectHandler} 在 insert/update 时统一填写，
 * 业务代码不需要（也不应该）自己 set，避免漏填口径不一致。</p>
 */
@Getter
@Setter
public abstract class AuditColumns {

    /**
     * 创建人ID（ssl_sys_user.id）；系统自动写入的行为 NULL
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long createdByUserId;

    /**
     * 创建人真实姓名（写入时快照）；系统自动写入的行固定为「系统」
     */
    @TableField(fill = FieldFill.INSERT)
    private String createdByName;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 修改人ID（ssl_sys_user.id）；系统自动写入的行为 NULL
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedByUserId;

    /**
     * 修改人真实姓名（写入时快照）；系统自动写入的行固定为「系统」
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedByName;

    /**
     * 修改时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
