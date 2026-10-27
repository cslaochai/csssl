package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.VerifyRequest;
import com.csssl.dto.VerifyResult;
import com.csssl.service.CaService;
import com.csssl.service.CrlService;
import com.csssl.service.OcspService;
import com.csssl.service.VerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * 业务系统公开接口（无需登录，供 cslk / csmig / csmis 等子系统对接）：
 * 根证书下载、证书链在线校验、CRL 下载、OCSP 三态查询（RFC 6960）。
 */
@Slf4j
@RestController
@RequestMapping("/ca/public")
@RequiredArgsConstructor
public class VerifyController {

    private final CaService caService;
    private final VerifyService verifyService;
    private final CrlService crlService;
    private final OcspService ocspService;

    /** 根证书 PEM 下载（各子系统信任链配置） */
    @GetMapping("/root")
    public String root() {
        return caService.exportRootPem();
    }

    /** 当前服务签发二级CA证书 PEM（中间CA链配置） */
    @GetMapping("/subca")
    public String subCa() {
        return caService.exportSubCaPem();
    }

    /** 证书链在线校验（TLS/SIGN 用途可选） */
    @PostMapping("/verify")
    public Result<VerifyResult> verify(@RequestBody VerifyRequest req) {
        return Result.success("校验完成", verifyService.verify(req));
    }

    /** CRL 下载（吊销列表，供业务系统缓存校验） */
    @GetMapping("/crl")
    public String crl() {
        return crlService.buildCrlPem();
    }

    /** 增量 CRL（dCRL）下载：自最近全量发布以来新增的吊销/挂起条目（RFC 5280 §5.2.4），
     *  依赖方用 DeltaCRLIndicator 与已缓存的全量 CRL 合并增量同步 */
    @GetMapping("/crl/delta")
    public String crlDelta() {
        return crlService.buildDeltaCrl().pem();
    }

    /** OCSP：POST 请求（Content-Type: application/ocsp-request，DER） */
    @PostMapping(value = "/ocsp", consumes = "application/ocsp-request",
            produces = "application/ocsp-response")
    public ResponseEntity<byte[]> ocspPost(@RequestBody byte[] body) {
        byte[] resp = ocspService.respond(body);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/ocsp-response")).body(resp);
    }

    /** OCSP：GET 请求（RFC 6960 base64url 编码的 DER） */
    @GetMapping(value = "/ocsp/{b64url}", produces = "application/ocsp-response")
    public ResponseEntity<byte[]> ocspGet(@PathVariable String b64url) {
        byte[] req = Base64.getUrlDecoder().decode(b64url);
        byte[] resp = ocspService.respond(req);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/ocsp-response")).body(resp);
    }
}
