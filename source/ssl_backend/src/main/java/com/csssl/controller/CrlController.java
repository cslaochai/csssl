package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CertVo;
import com.csssl.entity.CaCert;
import com.csssl.service.AuditService;
import com.csssl.service.CrlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 吊销列表（CRL）接口：管理端查看吊销记录与下载 CRL。
 */
@RestController
@RequestMapping("/admin/crl")
@RequiredArgsConstructor
public class CrlController {

    private final CrlService crlService;
    private final AuditService auditService;

    /** 吊销记录 + 当前 CRL PEM + thisUpdate/nextUpdate（管理页） */
    @GetMapping
    public Result<Map<String, Object>> view() {
        List<CaCert> revoked = crlService.revokedList();
        CrlService.CrlInfo info = crlService.publishIfNeeded();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revoked", revoked.stream().map(CertVo::from).toList());
        data.put("crlPem", revoked.isEmpty() ? "" : info.pem());
        data.put("thisUpdate", revoked.isEmpty() ? null : info.thisUpdate());
        data.put("nextUpdate", revoked.isEmpty() ? null : info.nextUpdate());
        data.put("entryCount", info.entries());
        data.put("issuerCn", info.issuerCn());
        return Result.success("查询成功", data);
    }

    /** 下载 CRL PEM（判重发布：内容未变直接复用已发布 CRL，不自增编号） */
    @GetMapping("/download")
    public String download(HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        String crlPem = crlService.publishIfNeeded().pem();
        auditService.record(username, "CRL_DOWNLOAD", null, "下载CRL吊销列表", clientIp(request));
        return crlPem;
    }

    /** 增量 CRL（dCRL）：自最近全量发布以来新增的吊销/挂起条目，供依赖方增量同步 */
    @GetMapping("/delta")
    public Result<Map<String, Object>> delta() {
        CrlService.DeltaCrlInfo info = crlService.buildDeltaCrl();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deltaPem", info.pem());
        data.put("thisUpdate", info.thisUpdate());
        data.put("nextUpdate", info.nextUpdate());
        data.put("entryCount", info.entries());
        data.put("crlNumber", info.crlNumber());
        data.put("baseCrlNumber", info.baseCrlNumber());
        return Result.success("查询成功", data);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
