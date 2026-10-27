package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.entity.AuditLog;
import com.csssl.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志接口（需登录）：管理操作留痕查询。
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
}
