package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书申请单审批轨迹（按步留痕：SUBMIT/ASSIGN/APPROVE/REJECT/ISSUE/CANCEL/URGE）。
 * 与防篡改审计链（ssl_audit_log）职责解耦：本表面向业务用户可见、按单据聚合，记录每一步的
 * 操作人、前后状态与意见，供申请详情页展示"当前环节 / 下一审批人"时间线。
 */
@Data
@TableName("ssl_application_trail")
public class ApplicationTrail {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 受理租户（统一社会信用代码；default=存量单根兼容值） */
    private String tenantUscc;

    /** 申请单 id */
    private Long applicationId;

    /** 申请单号（快照，便于检索） */
    private String applyNo;

    /** 步骤：SUBMIT/ASSIGN/APPROVE/REJECT/ISSUE/CANCEL/URGE */
    private String step;

    /** 操作人账号 */
    private String operatorUsername;

    /** 操作人真实姓名 */
    private String operatorRealName;

    /** 操作人角色编码 */
    private String operatorRole;

    /** 动作前状态 */
    private String fromStatus;

    /** 动作后状态 */
    private String toStatus;

    /** 意见/备注 */
    private String comment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
