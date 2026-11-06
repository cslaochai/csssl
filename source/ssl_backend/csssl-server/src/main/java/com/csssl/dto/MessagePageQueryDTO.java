package com.csssl.dto;

import com.csssl.common.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 我的消息分页查询（替换 MessageController#pageMine 的三个 @RequestParam）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MessagePageQueryDTO extends PageParam {
    /** null=全部；1=已读；0=未读 */
    private Integer readFlag;
}
