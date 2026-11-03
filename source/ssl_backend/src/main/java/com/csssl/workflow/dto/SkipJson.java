package com.csssl.workflow.dto;

import lombok.Data;

@Data
public class SkipJson {

    private String nowNodeCode;

    private String nextNodeCode;

    /** PASS / REJECT */
    private String skipType;

    private String skipName;

    private String coordinate;
}
