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
 * 审批轨迹：每次发起/通过/驳回/撤回都落一行。
 */
@Data
@TableName("flow_his_task")
public class FlowHisTask {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long definitionId;

    private String flowName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long instanceId;

    private Long taskId;

    private String businessId;

    private String nodeCode;

    private String nodeName;

    private Integer nodeType;

    private String targetNodeCode;

    private String targetNodeName;

    /**
     * 操作人账号
     */
    private String approver;

    /**
     * 操作人姓名快照
     */
    private String collaborator;

    private String skipType;

    private String flowStatus;

    private String message;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
