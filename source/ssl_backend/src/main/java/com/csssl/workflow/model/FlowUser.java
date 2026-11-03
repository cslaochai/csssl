package com.csssl.workflow.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务办理人：一条任务按权限标识解析出的候选人。
 */
@Data
@TableName("flow_user")
public class FlowUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 1=审批人 2=转办人 3=委托人
     */
    private String type;

    private String processedBy;

    private Long associated;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
