package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 二级CA 重建申请单逐人审批记录：5 名保管人各投一票（APPROVED / REJECTED），一人一票。
 */
@Data
@TableName("ssl_ca_subca_approval")
public class CaSubCaApproval {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关联申请单 id
     */
    private Long ticketId;

    /**
     * 审批人账号（须为该公司 5 名保管人之一）
     */
    private String approverUsername;

    /**
     * 审批人真实姓名
     */
    private String approverRealName;

    /**
     * 决定：APPROVED-同意 / REJECTED-驳回
     */
    private String decision;

    /**
     * 审批意见（驳回必填）
     */
    private String comment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime decidedAt;
}
