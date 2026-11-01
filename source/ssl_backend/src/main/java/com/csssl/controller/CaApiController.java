package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.config.CaApiAuthFilter;
import com.csssl.dto.ApiApplicationSubmitDTO;
import com.csssl.dto.ApiCancelDTO;
import com.csssl.entity.ApiClient;
import com.csssl.service.CaApiService;
import com.csssl.vo.ApiApplicationVO;
import com.csssl.vo.ApiCertStatusVO;
import com.csssl.vo.ApiTemplateVO;
import com.csssl.vo.ApiTrustVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对外证书申请 API v1（供 HIS / EMR 等外部系统直连）。
 *
 * <p>鉴权由 {@link CaApiAuthFilter} 完成（HMAC-SHA256 + 防重放 + 限流 + IP/证书白名单）；
 * URL 不携带租户标识——租户由 appId 反查，调用方无法指定其他租户的数据域。</p>
 */
@RestController
@RequestMapping("/ca/api/v1")
@RequiredArgsConstructor
public class CaApiController {

    private final CaApiService apiService;

    /**
     * 审计来源地址：与鉴权过滤器口径一致，只看 TCP 对端（X-Forwarded-For 可伪造，仅在有可信反代时另行配置）
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * 信任材料：根/中间证书 PEM 与 CRL/OCSP 地址
     */
    @GetMapping("/trust")
    public Result<ApiTrustVO> trust(@RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client) {
        return Result.success("查询成功", apiService.trust(client));
    }

    /**
     * 可用模板（全局 ∪ 本租户，排除 SUB_CA）
     */
    @GetMapping("/templates")
    public Result<List<ApiTemplateVO>> templates(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @RequestParam(required = false) String certType) {
        return Result.success("查询成功", apiService.templates(client, certType));
    }

    /**
     * 提交证书申请（externalRequestId 幂等）
     */
    @PostMapping("/applications")
    public Result<ApiApplicationVO> submit(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @RequestAttribute(CaApiAuthFilter.ATTR_BODY_SHA256) String bodyHash,
            @Valid @RequestBody ApiApplicationSubmitDTO body,
            HttpServletRequest request) {
        ApiApplicationVO vo = apiService.submit(client, body, bodyHash, clientIp(request));
        return Result.success(vo.isIdempotentReplay() ? "幂等重放，返回首次申请结果" : "申请提交成功", vo);
    }

    /**
     * 按申请单号查询状态
     */
    @GetMapping("/applications/{applyNo}")
    public Result<ApiApplicationVO> detail(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @PathVariable String applyNo) {
        return Result.success("查询成功", apiService.detail(client, applyNo));
    }

    /**
     * 按外部申请 ID 查询状态
     */
    @GetMapping("/applications")
    public Result<ApiApplicationVO> byExternalRequestId(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @RequestParam String externalRequestId) {
        return Result.success("查询成功", apiService.detailByExternalRequestId(client, externalRequestId));
    }

    /**
     * 下载证书：chain=true（默认）=叶子+SUB_CA+根，false=仅叶子；ISSUED 前返回 409
     */
    @GetMapping("/applications/{applyNo}/certificate")
    public ResponseEntity<String> download(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @PathVariable String applyNo,
            @RequestParam(defaultValue = "true") boolean chain) {
        CaApiService.CertificateDownload d = apiService.download(client, applyNo, chain);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-pem-file;charset=UTF-8"));
        if (d.serialHex() != null) {
            builder.header("X-Serial-Hex", d.serialHex());
        }
        if (d.fingerprintSha256() != null) {
            builder.header("X-Fingerprint-Sha256", d.fingerprintSha256());
        }
        if (d.notAfter() != null) {
            builder.header("X-Not-After", d.notAfter());
        }
        return builder.body(d.pem());
    }

    /**
     * 撤销申请（仅提交方；PENDING/APPROVED 可撤，已撤销幂等返回）
     */
    @PostMapping("/applications/{applyNo}/cancel")
    public Result<ApiApplicationVO> cancel(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @PathVariable String applyNo,
            @Valid @RequestBody(required = false) ApiCancelDTO body,
            HttpServletRequest request) {
        String reason = body == null ? null : body.getReason();
        return Result.success("已撤销", apiService.cancel(client, applyNo, reason, clientIp(request)));
    }

    /**
     * 按序列号查询证书状态（便捷接口；实时性要求高时以 OCSP 为准）
     */
    @GetMapping("/certificates/{serialHex}/status")
    public Result<ApiCertStatusVO> certStatus(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @PathVariable String serialHex) {
        return Result.success("查询成功", apiService.certStatus(client, serialHex));
    }
}
