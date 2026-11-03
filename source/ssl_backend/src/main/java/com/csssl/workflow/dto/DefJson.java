package com.csssl.workflow.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 流程设计器读写契约（与前端 WorkflowDesigner.vue 的 JSON 结构一一对应）。
 */
@Data
public class DefJson {

    /** 雪花 ID 超出 JS Number 安全整数范围，必须按字符串下发，否则前端回传时精度丢失 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String flowCode;

    private String flowName;

    private String category;

    private String version;

    private Integer isPublish;

    private String formCustom;

    private List<NodeJson> nodeList = new ArrayList<>();
}
