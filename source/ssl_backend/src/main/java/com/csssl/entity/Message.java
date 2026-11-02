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
 * 站内信 / 消息中心（GM/T 0034 分权制衡下，面向 CA 内部用户的待办与结果通知）。
 * 与 {@code HisPushService} 的出站回调不同：本表是面向内部用户的收件箱，支持未读红点与已读标记。
 */
@Data
@TableName("ssl_message")
public class Message {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属公司（统一社会信用代码） */
    private String companyUscc;

    /** 接收人账号 */
    private String recipientUsername;

    /** 接收人真实姓名 */
    private String recipientRealName;

    /** 发送人账号（系统催办留 SYSTEM） */
    private String senderUsername;

    /** 消息类型：APP_URGE-催办 APP_SUBMITTED-新待审 APP_APPROVED-已批准 APP_REJECTED-驳回 APP_ISSUED-已签发 */
    private String type;

    /** 消息标题 */
    private String title;

    /** 消息正文 */
    private String content;

    /** 关联业务：CERT_APPLICATION */
    private String bizType;

    /** 关联业务 ID（申请单 id） */
    private String bizId;

    /** 0-未读 1-已读 */
    private Integer readFlag;

    /** 阅读时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime readAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
