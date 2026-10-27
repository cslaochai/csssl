package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.AuditLog;
import com.csssl.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计：登录/签发/吊销/导出等管理操作留痕，等保审计用。
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /** 记录一条审计日志 */
    public void record(String username, String action, String target, String detail, String ip) {
        AuditLog log = new AuditLog();
        log.setUsername(username == null || username.isBlank() ? "SYSTEM" : username);
        log.setAction(action);
        log.setTarget(target);
        log.setDetail(detail);
        log.setIp(ip);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    /** 分页查询审计日志 */
    public Page<AuditLog> page(long pageNo, long pageSize, String action) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            qw.eq(AuditLog::getAction, action);
        }
        qw.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }
}
