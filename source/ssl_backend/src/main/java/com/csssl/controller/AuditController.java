package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.entity.AuditLog;
import com.csssl.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计日志接口（需登录）：管理操作留痕查询 + 防篡改验签（GM/T 0034 §10.1/§10.5）。
 */
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** 审计日志分页查询 */
    @GetMapping
    public Result<Page<AuditLog>> page(@RequestParam(defaultValue = "1") long pageNo,
                                       @RequestParam(defaultValue = "10") long pageSize,
                                       @RequestParam(required = false) String action) {
        return Result.success("查询成功", auditService.page(pageNo, pageSize, action));
    }

    /** 验签：校验某条审计记录未被篡改（改动任何字段都会失败） */
    @PostMapping("/verify")
    public Result<Map<String, Object>> verify(@org.springframework.web.bind.annotation.RequestBody Map<String, Long> body) {
        Long id = body.get("id");
        if (id == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("验签完成", auditService.verifyLog(id));
    }
}
