package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.entity.AuditLog;
import com.csssl.service.AuditService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计日志接口：管理操作留痕查询 + 防篡改验签（GM/T 0034 §10.1/§10.5）。
 *
 * <p>审计属监管职责，按分权制衡要求收口给审计员（AUDITOR）单独查看，
 * 系统管理员/操作员不得读审计（防"做事的人删自己的痕迹"）。</p>
 */
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** 审计日志分页查询（仅审计员） */
    @GetMapping
    public Result<Page<AuditLog>> page(@RequestParam(defaultValue = "1") long pageNo,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String action,
                                      HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        return Result.success("查询成功", auditService.page(pageNo, pageSize, action));
    }

    /** 验签：校验某条审计记录未被篡改（改动任何字段都会失败；仅审计员） */
    @PostMapping("/verify")
    public Result<Map<String, Object>> verify(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        Long id = body.get("id");
        if (id == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("验签完成", auditService.verifyLog(id));
    }

    /** 审计归档导出（≥5年留存材料）：CSV（UTF-8 BOM）+ 审计报告 SM2 签名，归档文件完整性可验签（仅审计员） */
    @GetMapping("/export")
    public Result<Map<String, Object>> export(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        return Result.success("导出完成", auditService.exportCsv());
    }

    /**
     * 哈希链对账（仅审计员）：区间可空=全量，分页扫描按 id 升序逐条核验签名与 prev_id/prev_sign 衔接。
     * 检出删除（前驱指向缺失）、分叉与篡改改写。
     */
    @GetMapping("/chain-verify")
    public Result<Map<String, Object>> chainVerify(@RequestParam(required = false) Long fromId,
                                                  @RequestParam(required = false) Long toId,
                                                  HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        return Result.success("链对账完成", auditService.verifyChain(fromId, toId));
    }
}
