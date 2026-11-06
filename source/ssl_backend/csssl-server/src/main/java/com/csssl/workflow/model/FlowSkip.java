package com.csssl.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点跳转：PASS=通过 REJECT=驳回。
 */
@Data
@TableName("flow_skip")
public class FlowSkip {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long definitionId;

    private Long nodeId;

    private String nowNodeCode;

    private Integer nowNodeType;

    private String nextNodeCode;

    private Integer nextNodeType;

    private String skipName;

    private String skipType;

    private String skipCondition;

    private String coordinate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
