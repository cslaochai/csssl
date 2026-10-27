package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.AuditLog;
import com.csssl.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 操作审计：登录/签发/吊销/导出等管理操作留痕（等保审计用）。
 * 每条记录按 GM/T 0034 §10.1/§10.5 做执行人数字签名（SM3withSM2），日志只增不删改，
 * 事后改动任一字都会验签失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AuditLogMapper auditLogMapper;
    private final AuditSigner auditSigner;

    /** 记录一条审计日志（自动签名防篡改） */
    public void record(String username, String action, String target, String detail, String ip) {
        AuditLog logRow = new AuditLog();
        logRow.setUsername(username == null || username.isBlank() ? "SYSTEM" : username);
        logRow.setAction(action);
        logRow.setTarget(target);
        logRow.setDetail(detail);
        logRow.setIp(ip);
        logRow.setCreatedAt(LocalDateTime.now());
        try {
            logRow.setSign(auditSigner.sign(canonical(logRow)));
        } catch (Exception e) {
            // 签名失败不阻断审计留痕（记录仍入库），仅告警
            log.warn("[审计] 签名失败，记录不带签名入库: {}", e.getMessage());
        }
        auditLogMapper.insert(logRow);
    }

    /** 审计记录规范化串（验签用；与签名时逐字一致） */
    public static String canonical(AuditLog logRow) {
        return String.join("|",
                nz(logRow.getUsername()), nz(logRow.getAction()), nz(logRow.getTarget()),
                nz(logRow.getDetail()), nz(logRow.getIp()),
                logRow.getCreatedAt() == null ? "" : TS.format(logRow.getCreatedAt()));
    }

    /** 验签：按 id 读取记录并校验签名；返回 {valid, canonical}（改过任一字段即 false） */
    public Map<String, Object> verifyLog(Long id) {
        AuditLog logRow = auditLogMapper.selectById(id);
        if (logRow == null) {
            throw new IllegalArgumentException("审计记录不存在（id=" + id + "）");
        }
        String canon = canonical(logRow);
        boolean valid = logRow.getSign() != null && !logRow.getSign().isBlank()
                && auditSigner.verify(canon, logRow.getSign());
        return Map.of(
                "id", id,
                "valid", valid,
                "message", valid ? "签名有效：记录未被篡改，由审计签名密钥签发"
                        : "签名校验失败：记录内容已被改动或签名缺失",
                "canonical", canon,
                "sign", logRow.getSign() == null ? "" : logRow.getSign());
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
