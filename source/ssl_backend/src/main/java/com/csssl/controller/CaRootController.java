package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CaStatusVo;
import com.csssl.service.AuditService;
import com.csssl.service.CaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 根CA管理接口（需登录）：状态查询、初始化、根证书导出。
 */
@RestController
@RequestMapping("/admin/ca")
@RequiredArgsConstructor
public class CaRootController {

    private final CaService caService;
    private final AuditService auditService;

    /** 根CA状态（含未初始化原因） */
    @GetMapping("/status")
    public Result<CaStatusVo> status() {
        return Result.success("查询成功", caService.status());
    }

    /** 初始化根CA（幂等：已存在则原样返回；私钥丢失则吊销旧根并重建） */
    @PostMapping("/init")
    public Result<CaStatusVo> init(HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        CaStatusVo vo = caService.initRoot();
        auditService.record(username, "CA_INIT", null,
                "初始化根CA：" + vo.getCn() + "（序列号 " + vo.getSerialHex() + "）", clientIp(request));
        return Result.success("根CA初始化完成", vo);
    }

    /** 导出根证书 PEM（供各子系统信任链配置） */
    @GetMapping("/root/download")
    public String downloadRoot(HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        auditService.record(username, "CERT_DOWNLOAD", "ROOT", "导出根CA证书", clientIp(request));
        return caService.exportRootPem();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
