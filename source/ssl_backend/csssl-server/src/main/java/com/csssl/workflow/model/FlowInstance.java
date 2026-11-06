package com.csssl.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程实例：业务单据与流程定义的绑定，保存当前所在节点。
 */
@Data
@TableName("flow_instance")
public class FlowInstance {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long definitionId;

    private String flowName;

    private String businessId;

    /**
     * 发起人（撤回只能由其本人操作）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long startedByUserId;

    private String startedByName;

    private Integer nodeType;

    private String nodeCode;

    private String nodeName;

    private String variable;

    /**
     * RUNNING / APPROVED / REJECTED / CANCELED
     */
    private String flowStatus;

    private Integer activityStatus;

    private String defJson;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
