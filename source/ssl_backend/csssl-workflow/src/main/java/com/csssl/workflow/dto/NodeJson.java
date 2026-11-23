package com.csssl.workflow.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeJson {

    private String nodeCode;

    private String nodeName;

    /** 0=开始 1=中间审批 2=结束 */
    private Integer nodeType;

    private String permissionFlag;

    /** 0.000=或签 100.000=会签 60.000=票签60% */
    private String nodeRatio;

    private String coordinate;

    private String version;

    private List<SkipJson> skipList = new ArrayList<>();

    /** 链式设置跳转线，便于种子初始化书写。 */
    public NodeJson withSkips(List<SkipJson> skips) {
        this.skipList = skips;
        return this;
    }
}
