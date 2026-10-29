package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色档案视图对象：角色配置页列表与账号页角色下拉共用。
 */
@Data
public class RoleVo {

    /**
     * 角色ID（JSON 输出为字符串，避免前端 Long 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 角色编码（六类法定角色，不可改）
     */
    private String roleCode;

    /**
     * 角色名称
     */
    private String roleName;

    /**
     * 职责说明
     */
    private String roleDesc;

    /**
     * 排序号
     */
    private Integer sortOrder;

    /**
     * 状态：1 启用 / 0 停用
     */
    private Integer status;

    /**
     * 内置角色：1 是 / 0 否
     */
    private Integer builtin;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 该角色账号数
     */
    private long userCount;

    /**
     * 已配置权限点数
     */
    private int permCount;

    /**
     * 法定权限上限数
     */
    private int maxPermCount;
}
