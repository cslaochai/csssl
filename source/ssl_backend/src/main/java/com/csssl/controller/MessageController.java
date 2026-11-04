package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.MessagePageQueryDTO;
import com.csssl.entity.LoginUser;
import com.csssl.entity.Message;
import com.csssl.service.MessageService;
import com.csssl.util.UserUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内信 / 消息中心（面向 CA 内部用户本人；消息私有，任何登录用户可读自己的消息）。
 */
@RestController
@RequestMapping("/admin/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 我的消息（分页）
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/page")
    public Result<Page<Message>> pageMine(@Valid @RequestBody MessagePageQueryDTO body) {
        LoginUser u = UserUtils.requireLoginUser();
        return Result.success("查询成功", messageService.pageMy(u.getUserId(), body.getPageNo(), body.getPageSize(), body.getReadFlag()));
    }

    /**
     * 未读消息数（登录后铃铛红点）
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/unread-count")
    public Result<Long> unreadCount() {
        LoginUser u = UserUtils.requireLoginUser();
        return Result.success("查询成功", messageService.unreadCount(u.getUserId()));
    }

    /**
     * 标记已读（body: ids 数组，空=标记全部已读）
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/read")
    public Result<Void> markRead(@RequestBody(required = false) List<Long> ids) {
        LoginUser u = UserUtils.requireLoginUser();
        messageService.markRead(u.getUserId(), ids);
        return Result.success("已标记已读", null);
    }
}
