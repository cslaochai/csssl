package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CaStatusVo;
import com.csssl.service.AuditService;
import com.csssl.service.CaService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 根CA管理接口（需登录）：状态查询、初始化、根密钥封存/解封、证书导出。
 */
@RestController
@RequestMapping("/admin/ca")
@RequiredArgsConstructor
public class CaRootController {

    private final CaService caService;
    private final AuditService auditService;

    /** 根CA状态（含 SUB_CA 与根密钥封存状态） */
    @GetMapping("/status")
    public Result<CaStatusVo> status() {
        return Result.success("查询成功", caService.status());
    }

    /** 初始化根CA（幂等：已存在则原样返回；私钥丢失则吊销旧根并重建；仅系统管理员） */
    @PostMapping("/init")
    public Result<CaStatusVo> init(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        CaStatusVo vo = caService.initRoot();
        auditService.record(username, "CA_INIT", null,
                "初始化根CA：" + vo.getCn() + "（序列号 " + vo.getSerialHex() + "）", clientIp(request));
        return Result.success("根CA初始化完成", vo);
    }

    /** 封存根私钥（离线化）：日常签发由 SUB_CA 承担，根私钥不再参与在线操作；仅系统管理员 */
    @PostMapping("/seal")
    public Result<CaStatusVo> seal(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        caService.sealRootKey();
        auditService.record(username, "CA_SEAL", null, "封存根CA私钥（离线化）", clientIp(request));
        CaStatusVo vo = caService.status();
        return Result.success("根CA私钥已封存离线，日常签发由 SUB_CA 承担", vo);
    }

    /** 解封根私钥（恢复在线态，仅用于 SUB_CA 轮换/重建等维护操作；仅系统管理员） */
    @PostMapping("/unseal")
    public Result<CaStatusVo> unseal(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        caService.unsealRootKey();
        auditService.record(username, "CA_UNSEAL", null, "解封根CA私钥", clientIp(request));
        return Result.success("根CA私钥已解封，恢复在线态", caService.status());
    }

    /** 导出根证书 PEM（供各子系统信任链配置） */
    @GetMapping("/root/download")
    public String downloadRoot(HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        auditService.record(username, "CERT_DOWNLOAD", "ROOT", "导出根CA证书", clientIp(request));
        return caService.exportRootPem();
    }

    /** 导出当前 SUB_CA 证书 PEM（中间CA链配置） */
    @GetMapping("/subca/download")
    public String downloadSubCa(HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        auditService.record(username, "CERT_DOWNLOAD", "SUB_CA", "导出服务签发二级CA证书", clientIp(request));
        return caService.exportSubCaPem();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
