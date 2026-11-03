package com.csssl.workflow.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StartRequest {

    private String flowCode;

    private String businessId;

    private Map<String, Object> variables;
}
