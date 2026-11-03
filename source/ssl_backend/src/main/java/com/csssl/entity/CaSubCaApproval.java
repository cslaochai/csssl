package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 二级CA 重建申请单逐人审批记录：5 名保管人各投一票（APPROVED / REJECTED），一人一票。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_ca_subca_approval")
public class CaSubCaApproval extends AuditColumns {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 关联申请单 id
     */
    private Long ticketId;

    /**
     * 审批人ID（ssl_sys_user.id，须为该公司 5 名保管人之一）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long approverUserId;

    /**
     * 审批人真实姓名（写入时快照，不回查用户表）
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

    /**
     * 审批人提交的根私钥份额（秘钥片）JSON（index/total/threshold/yHex）。
     * 审批同意时由保管人粘贴本人持有的那份；3 名保管人通过即收集到 ≥3 份，用于执行重建解封根私钥。
     * 驳回或未提交时为空。
     */
    @TableField("share_json")
    private String shareJson;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime decidedAt;
}
