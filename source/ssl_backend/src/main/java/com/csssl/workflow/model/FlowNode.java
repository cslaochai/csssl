package com.csssl.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程节点：0=开始 1=中间审批 2=结束。
 */
@Data
@TableName("flow_node")
public class FlowNode {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long definitionId;

    private String nodeCode;

    private String nodeName;

    private Integer nodeType;

    /**
     * 权限标识，多个用 @@ 分隔，如 role:APPROVER@@role:RA_ADMIN
     */
    private String permissionFlag;

    /**
     * 签核比例：0=或签 100=会签 60=票签60%
     */
    private String nodeRatio;

    private String coordinate;

    private String version;

    private String anyNodeSkip;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
