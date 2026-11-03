package com.csssl.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待办任务：一个节点可产生多条（会签/票签），flowStatus 既是状态也是并发 CAS 的判据。
 */
@Data
@TableName("flow_task")
public class FlowTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long definitionId;

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

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
