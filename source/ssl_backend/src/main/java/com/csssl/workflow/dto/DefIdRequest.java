package com.csssl.workflow.dto;

import lombok.Data;

/**
 * 流程定义操作请求体。
 *
 * <p>雪花 ID 不放在 URL 路径上，统一走请求体，避免前端回传时精度丢失。</p>
 */
@Data
public class DefIdRequest {

    private Long id;
}
