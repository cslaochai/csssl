package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CertVo;
import com.csssl.dto.CsrIssueRequest;
import com.csssl.dto.IssueCertRequest;
import com.csssl.service.AuditService;
import com.csssl.service.CertService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 证书管理接口（需登录）：列表/详情/签发/CSR签发/吊销/下载/概览。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class CertController {

    private final CertService certService;
    private final AuditService auditService;

    /** 证书列表（分页 + 类型/状态/关键字筛选） */
    @GetMapping("/certs")
    public Result<Page<CertVo>> page(@RequestParam(defaultValue = "1") long pageNo,
                                     @RequestParam(defaultValue = "10") long pageSize,
                                     @RequestParam(required = false) String certType,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        return Result.success("查询成功", certService.page(pageNo, pageSize, certType, status, keyword));
    }

    /** 证书详情 */
    @GetMapping("/certs/{id}")
    public Result<CertVo> detail(@PathVariable Long id) {
        return Result.success("查询成功", certService.detail(id));
    }

    /** 在线签发（内部生成密钥对，私钥落盘） */
    @PostMapping("/certs")
    public Result<CertVo> issue(@RequestBody IssueCertRequest req, HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        return Result.success("签发成功", certService.issue(req, username, clientIp(request)));
    }

    /** CSR 签发（主体与公钥取自 CSR） */
    @PostMapping("/certs/csr")
    public Result<CertVo> issueFromCsr(@RequestBody CsrIssueRequest req, HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        return Result.success("CSR签发成功", certService.issueFromCsr(
                req.getCsrPem(), req.getCertType(), req.getValidityDays(),
                req.getSan(), req.getDescription(), username, clientIp(request)));
    }

    /** 公钥签发（RA 场景）：申请方自持私钥，仅提交 Subject DN + 公钥 PEM（HIS 医生签名证书对接） */
    @PostMapping("/certs/public-key")
    public Result<CertVo> issueWithPublicKey(@RequestBody com.csssl.dto.PublicKeyIssueRequest req,
                                             HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        return Result.success("公钥签发成功", certService.issueWithPublicKey(req, username, clientIp(request)));
    }

    /** 吊销证书 */
    @PostMapping("/certs/{id}/revoke")
    public Result<CertVo> revoke(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                 HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        String reason = body == null ? null : body.get("reason");
        return Result.success("吊销成功", certService.revoke(id, reason, username, clientIp(request)));
    }

    /** 下载证书 PEM（公钥证书） */
    @GetMapping("/certs/{id}/cert.pem")
    public String downloadCert(@PathVariable Long id, HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "下载证书：" + certService.detail(id).getSubjectCn(), clientIp(request));
        return certService.certPem(id);
    }

    /** 下载私钥 PEM（受 allow-private-key-download 开关控制） */
    @GetMapping("/certs/{id}/key.pem")
    public String downloadKey(@PathVariable Long id, HttpServletRequest request) {
        String username = (String) request.getAttribute("ssl.username");
        auditService.record(username, "CERT_DOWNLOAD", String.valueOf(id),
                "下载私钥：" + certService.detail(id).getSubjectCn(), clientIp(request));
        return certService.keyPem(id);
    }

    /** 概览统计（仪表盘） */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success("查询成功", certService.overview());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
