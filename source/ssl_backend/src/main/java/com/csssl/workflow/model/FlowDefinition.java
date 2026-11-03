package com.csssl.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程定义（一行 = 一个流程，发布状态由 isPublish 控制）。
 */
@Data
@TableName("flow_definition")
public class FlowDefinition {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String flowCode;

    private String flowName;

    private String modelValue;

    private String category;

    private String version;

    /**
     * 0=草稿 1=已发布
     */
    private Integer isPublish;

    private String formCustom;

    private Integer activityStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
