package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.AuditMetaObjectHandler;
import com.csssl.entity.LoginUser;
import com.csssl.entity.Message;
import com.csssl.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信 / 消息中心（面向 CA 内部用户的待办与结果通知）。
 *
 * <p>收发件人一律 {@code ssl_sys_user.id} + 真实姓名快照：账号是凭证，不入业务表也不下发前端。</p>
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;

    /**
     * 发消息（催办/审批结果通知统一入口）：收件人按 user_id 归属；
     * sender 为空表示系统自动通知（sender_user_id 留空、姓名写「系统」）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void send(String companyUscc, Long recipientUserId, String recipientRealName,
                     LoginUser sender, String type, String title, String content,
                     String bizType, String bizId) {
        sendAs(companyUscc, recipientUserId, recipientRealName,
                sender == null ? null : sender.getUserId(),
                sender == null ? null : sender.getRealName(),
                type, title, content, bizType, bizId);
    }

    /**
     * 发消息（发件人显式指定口径）：无登录态的自动流转与对外 API 通道用，
     * senderUserId 留空、senderRealName 写「系统」或 API 客户端名称（不为外部通道伪造自然人账号）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendAs(String companyUscc, Long recipientUserId, String recipientRealName,
                       Long senderUserId, String senderRealName, String type, String title, String content,
                       String bizType, String bizId) {
        Message m = new Message();
        m.setCompanyUscc(companyUscc == null || companyUscc.isBlank() ? "default" : companyUscc);
        m.setRecipientUserId(recipientUserId);
        m.setRecipientRealName(recipientRealName);
        m.setSenderUserId(senderUserId);
        m.setSenderRealName(senderRealName == null || senderRealName.isBlank()
                ? AuditMetaObjectHandler.SYSTEM_NAME : senderRealName);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setBizType(bizType);
        m.setBizId(bizId);
        m.setReadFlag(0);
        messageMapper.insert(m);
    }

    /** 当前登录人的消息分页（仅本人；readFlag=0 仅未读，null=全部） */
    public Page<Message> pageMy(Long userId, long pageNo, long pageSize, Integer readFlag) {
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<Message>()
                .eq(Message::getRecipientUserId, userId)
                .eq(readFlag != null, Message::getReadFlag, readFlag)
                .orderByDesc(Message::getCreatedAt);
        return messageMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 未读消息数（铃铛红点） */
    public long unreadCount(Long userId) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getRecipientUserId, userId)
                .eq(Message::getReadFlag, 0));
    }

    /** 标记已读（单条或批量 id；ids 为空=标记全部已读） */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, List<Long> ids) {
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getRecipientUserId, userId)
                .in(ids != null && !ids.isEmpty(), Message::getId, ids)
                .set(Message::getReadFlag, 1)
                .set(Message::getReadAt, LocalDateTime.now()));
    }
}
