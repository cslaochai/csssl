package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CaStatusVo;
import com.csssl.service.AuditService;
import com.csssl.service.CaService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 根CA管理接口（需登录）：状态查询、初始化、根密钥封存/解封、Shamir(3,5) 门限分持、证书导出。
 */
@RestController
@RequestMapping("/admin/ca")
@RequiredArgsConstructor
public class CaRootController {

    private final CaService caService;
    private final AuditService auditService;

    /** 根CA状态（含 SUB_CA 与根密钥封存/门限状态） */
    @GetMapping("/status")
    public Result<CaStatusVo> status() {
        return Result.success("查询成功", caService.status());
    }

    /** 初始化根CA（幂等：已存在则原样返回；私钥丢失则吊销旧根并重建；系统管理员/密钥管理员） */
    @PostMapping("/init")
    public Result<CaStatusVo> init(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        String username = RoleCode.currentUser(request);
        CaStatusVo vo = caService.initRoot();
        auditService.record(username, "CA_INIT", null,
                "初始化根CA：" + vo.getCn() + "（序列号 " + vo.getSerialHex() + "）", clientIp(request));
        return Result.success("根CA初始化完成", vo);
    }

    /** 封存根私钥（离线化/清除门限解封缓存）：日常签发由 SUB_CA 承担；系统管理员/密钥管理员 */
    @PostMapping("/seal")
    public Result<CaStatusVo> seal(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        String username = RoleCode.currentUser(request);
        caService.sealRootKey();
        auditService.record(username, "CA_SEAL", null, "封存根CA私钥（离线化）", clientIp(request));
        CaStatusVo vo = caService.status();
        return Result.success("根CA私钥已封存离线，日常签发由 SUB_CA 承担", vo);
    }

    /** 解封根私钥（旧模式恢复在线态；门限分持态请走份额解封接口；系统管理员/密钥管理员） */
    @PostMapping("/unseal")
    public Result<CaStatusVo> unseal(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        String username = RoleCode.currentUser(request);
        caService.unsealRootKey();
        auditService.record(username, "CA_UNSEAL", null, "解封根CA私钥", clientIp(request));
        return Result.success("根CA私钥已解封，恢复在线态", caService.status());
    }

    // ==================== Shamir(3,5) 门限分持 ====================

    /** 门限份额状态（5 份各自存在性 + 阈值） */
    @GetMapping("/shares")
    public Result<Map<String, Object>> shares(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        return Result.success("查询成功", caService.shareState());
    }

    /**
     * 门限化/迁移：把当前 SM4 加密落盘的根私钥拆为 Shamir(3,5) 份额并删除单点加密文件。
     * 新根已自动门限化；旧环境（已封存/在线）显式调用本接口迁移。
     */
    @PostMapping("/shares/migrate")
    public Result<Map<String, Object>> migrateShares(HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        String username = RoleCode.currentUser(request);
        Map<String, Object> state = caService.migrateRootShares();
        auditService.record(username, "CA_SHARE_GENERATE", "ROOT",
                "根私钥切换为 Shamir(3,5) 门限分持（5 份份额落盘，原始加密文件已删除）", clientIp(request));
        return Result.success("根私钥已切换为 Shamir(3,5) 门限分持，请下载并离线保管 5 份份额", state);
    }

    /** 下载单份门限份额（JSON 文本，供密钥管理员离线保管；建议 5 份分人分管） */
    @GetMapping("/shares/{index}")
    public Result<Map<String, Object>> downloadShare(@PathVariable int index, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        if (index < 1 || index > CaService.SHARE_TOTAL) {
            throw new IllegalArgumentException("份额编号无效（有效范围 1~" + CaService.SHARE_TOTAL + "）");
        }
        String json = caService.readShareJson(index);
        String username = RoleCode.currentUser(request);
        auditService.record(username, "CA_SHARE_DOWNLOAD", "SHARE-" + index,
                "导出第 " + index + " 份根密钥门限份额（请离线保管）", clientIp(request));
        @SuppressWarnings("unchecked")
        Map<String, Object> share;
        try {
            share = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("份额文件解析失败: " + e.getMessage(), e);
        }
        return Result.success("第 " + index + " 份份额", share);
    }

    /** 份额解封：提交任意 3 份门限份额重组根私钥（仅内存，用后请封存清除） */
    @PostMapping("/unseal-shares")
    public Result<CaStatusVo> unsealShares(@RequestBody Map<String, List<String>> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.KEY_ADMIN);
        List<String> shares = body == null ? null : body.get("shares");
        String username = RoleCode.currentUser(request);
        caService.unsealRootKeyWithShares(shares);
        auditService.record(username, "CA_UNSEAL_SHARES", "ROOT",
                "通过 " + (shares == null ? 0 : shares.size()) + " 份门限份额解封根私钥（内存缓存）", clientIp(request));
        return Result.success("根私钥已通过 " + (shares == null ? 0 : shares.size()) + " 份份额解封（内存缓存），可进行 SUB_CA 轮换等维护", caService.status());
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
