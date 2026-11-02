package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.Message;
import com.csssl.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信 / 消息中心（面向 CA 内部用户的待办与结果通知）。
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;

    /** 发消息（系统催办/审批结果通知统一入口） */
    @Transactional(rollbackFor = Exception.class)
    public void send(String companyUscc, String recipientUsername, String recipientRealName,
                     String senderUsername, String type, String title, String content,
                     String bizType, String bizId) {
        Message m = new Message();
        m.setCompanyUscc(companyUscc == null || companyUscc.isBlank() ? "default" : companyUscc);
        m.setRecipientUsername(recipientUsername);
        m.setRecipientRealName(recipientRealName);
        m.setSenderUsername(senderUsername);
        m.setType(type);
        m.setTitle(title);
        m.setContent(content);
        m.setBizType(bizType);
        m.setBizId(bizId);
        m.setReadFlag(0);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);
    }

    /** 当前登录人的消息分页（仅本人；readFlag=0 仅未读，null=全部） */
    public Page<Message> pageMy(String username, long pageNo, long pageSize, Integer readFlag) {
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<Message>()
                .eq(Message::getRecipientUsername, username)
                .eq(readFlag != null, Message::getReadFlag, readFlag)
                .orderByDesc(Message::getCreatedAt);
        return messageMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 未读消息数（铃铛红点） */
    public long unreadCount(String username) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getRecipientUsername, username)
                .eq(Message::getReadFlag, 0));
    }

    /** 标记已读（单条或批量 id；ids 为空=标记全部已读） */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String username, List<Long> ids) {
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getRecipientUsername, username)
                .in(ids != null && !ids.isEmpty(), Message::getId, ids)
                .set(Message::getReadFlag, 1)
                .set(Message::getReadAt, LocalDateTime.now()));
    }
}
