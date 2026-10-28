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
 *
 * <p>路线A数据域口径：机构审计员强制本机构过滤（显式传别机构 403）；
 * 全局审计员可按 ?orgKey= 逐机构查看，不传=跨机构全量——审计域本就属监督职能，
 * 全局只读总览为合规设计，不构成写旁路。</p>
 */
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** 审计日志分页查询（仅审计员；机构用户强制本机构，全局用户 orgKey 缺省=全量只读） */
    @GetMapping
    public Result<Page<AuditLog>> page(@RequestParam(defaultValue = "1") long pageNo,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String orgKey,
                                      HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        return Result.success("查询成功", auditService.page(pageNo, pageSize, action,
                RoleCode.readOrgScope(request, orgKey)));
    }

    /**
     * 验签：校验某条审计记录未被篡改（改动任何字段都会失败；仅审计员）。
     * 机构用户仅可验本机构记录（行级数据域校验，越域 403）；全局用户跨机构放行（只读监督职能）。
     */
    @PostMapping("/verify")
    public Result<Map<String, Object>> verify(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        Long id = body.get("id");
        if (id == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        AuditLog row = auditService.load(id);
        RoleCode.requireRowVisible(request, AuditService.orgOf(row));
        return Result.success("验签完成", auditService.verifyLog(id));
    }

    /**
     * 审计归档导出（≥5年留存材料）：CSV（UTF-8 BOM）+ 审计报告 SM2 签名，归档文件完整性可验签（仅审计员）。
     * 机构用户强制导出本机构；全局用户 orgKey 缺省=跨机构全量导出（只读放行，合规设计）。
     */
    @GetMapping("/export")
    public Result<Map<String, Object>> export(@RequestParam(required = false) String orgKey,
                                              HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        return Result.success("导出完成", auditService.exportCsv(RoleCode.readOrgScope(request, orgKey)));
    }

    /**
     * 哈希链对账（仅审计员）：区间可空=全量，分页扫描按 id 升序逐条核验签名与 prev_id/prev_sign 衔接。
     * 检出删除（前驱指向缺失）、分叉与篡改改写。
     *
     * <p><b>保持全局链视角，不分机构（有意设计）</b>：审计链为全局单链，prev_id 跨机构连续，
     * 链构造与对账都不按机构分段——分段会把跨机构相邻行的 prev 引用误判断链，反而不可审计。
     * 机构数据域过滤只作用于"查"（分页/验签/导出），对账结果仅暴露断链 id 与原因，不含记录内容。</p>
     */
    @GetMapping("/chain-verify")
    public Result<Map<String, Object>> chainVerify(@RequestParam(required = false) Long fromId,
                                                  @RequestParam(required = false) Long toId,
                                                  HttpServletRequest request) {
        RoleCode.require(request, RoleCode.AUDITOR);
        return Result.success("链对账完成", auditService.verifyChain(fromId, toId));
    }
}
