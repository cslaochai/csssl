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
 * 待办任务：一个节点可产生多条（会签/票签），flowStatus 既是状态也是并发 CAS 的判据。
 */
@Data
@TableName("flow_task")
public class FlowTask {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long definitionId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long instanceId;

    private String flowName;

    private String businessId;

    private String nodeCode;

    private String nodeName;

    private Integer nodeType;

    /**
     * PENDING / PASS / REJECT / CANCELED
     */
    private String flowStatus;

    private String formCustom;

    private String formPath;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
