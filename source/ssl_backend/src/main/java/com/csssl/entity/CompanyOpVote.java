package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公司变更单保管人投票记录（重新指派保管人专用）：
 * 发起人=公司管理员，投票人=提交时锁定的原 5 名密钥保管人，每人一票（同意/驳回），
 * 同意数超过 3（≥4）即执行重新绑定，驳回数 ≥2（无法再凑齐 4 同意）即否决。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ssl_company_op_vote")
public class CompanyOpVote extends AuditColumns {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 关联变更单 id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ticketId;

    /**
     * 投票人ID（ssl_sys_user.id，须在变更单 voterUserIds 快照内）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voterUserId;

    /**
     * 投票人真实姓名（写入时快照，不回查用户表；展示用）
     */
    private String voterRealName;

    /**
     * 决定：AGREE-同意 REJECT-驳回
     */
    private String decision;

    /**
     * 意见（驳回必填）
     */
    private String comment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime votedAt;
}
