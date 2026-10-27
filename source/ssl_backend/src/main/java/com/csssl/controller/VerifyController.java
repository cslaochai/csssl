package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.VerifyRequest;
import com.csssl.dto.VerifyResult;
import com.csssl.service.CaService;
import com.csssl.service.CrlService;
import com.csssl.service.VerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务系统公开接口（无需登录，供 cslk / csmig / csmis 等子系统对接）：
 * 根证书下载、证书链在线校验、CRL 下载。
 */
@Slf4j
@RestController
@RequestMapping("/ca/public")
@RequiredArgsConstructor
public class VerifyController {

    private final CaService caService;
    private final VerifyService verifyService;
    private final CrlService crlService;

    /** 根证书 PEM 下载（各子系统信任链配置） */
    @GetMapping("/root")
    public String root() {
        return caService.exportRootPem();
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
}
