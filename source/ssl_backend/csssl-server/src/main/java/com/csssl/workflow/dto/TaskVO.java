package com.csssl.workflow.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待办任务视图（附带节点权限标识，供前端展示“谁能办”）。
 */
@Data
public class TaskVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long instanceId;

    private String flowName;

    private String businessId;

    private String nodeCode;

    private String nodeName;

    private String permissionFlag;

    private String flowStatus;

    private LocalDateTime createTime;
}
