package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.VerifyQueryDTO;
import com.csssl.vo.VerifyResultVO;
import com.csssl.service.CaService;
import com.csssl.service.CrlService;
import com.csssl.service.OcspService;
import com.csssl.service.CompanyService;
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
 *
 * <p>路线A多公司：公司域公开端点 /ca/public/{companyUscc}/...（root/subca/crl/crl/delta/ocsp/verify），
 * 全部委托 per-company 服务方法；无 {companyUscc} 的现存端点保持 default 域行为，一行不动。
 * 合规点：公司不存在按现有异常口径抛 IllegalArgumentException（404/400 语义统一走全局异常处理），
 * 但 SUSPENDED/CLOSED 公司仍允许查 root/crl/ocsp/verify——历史证书必须持续可验证，
 * 只读端点绝不因公司状态关闭（公司状态门禁只作用于签发，见 CertService）。</p>
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
    private final CompanyService companyService;

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
    public Result<VerifyResultVO> verify(@RequestBody VerifyQueryDTO req) {
        return Result.success("校验完成", verifyService.verify(req));
    }

    /** CRL 下载（吊销列表，供业务系统缓存校验）：判重发布，内容未变复用已发布 CRL，不再每次 GET 自增 CRLNumber */
    @GetMapping("/crl")
    public String crl() {
        return crlService.publishIfNeeded().pem();
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

    // ==================== 公司域公开端点（路线A） ====================

    /** 公司根证书 PEM 下载（default 亦可走此路由，与 /root 等价） */
    @GetMapping("/{companyUscc}/root")
    public String companyRoot(@PathVariable String companyUscc) {
        requireCompanyExists(companyUscc);
        return caService.exportRootPem(companyUscc);
    }

    /** 公司当前 SUB_CA 证书 PEM（中间CA链配置） */
    @GetMapping("/{companyUscc}/subca")
    public String companySubCa(@PathVariable String companyUscc) {
        requireCompanyExists(companyUscc);
        return caService.exportSubCaPem(companyUscc);
    }

    /** 公司域证书链在线校验（沿该公司根链验证） */
    @PostMapping("/{companyUscc}/verify")
    public Result<VerifyResultVO> companyVerify(@PathVariable String companyUscc, @RequestBody VerifyQueryDTO req) {
        requireCompanyExists(companyUscc);
        return Result.success("校验完成", verifyService.verify(req, companyUscc));
    }

    /** 公司域 CRL 下载（判重发布逻辑同 default，per-company 独立编号） */
    @GetMapping("/{companyUscc}/crl")
    public String companyCrl(@PathVariable String companyUscc) {
        requireCompanyExists(companyUscc);
        return crlService.publishIfNeeded(companyUscc).pem();
    }

    /** 公司域增量 CRL（dCRL）下载：该域自最近全量发布以来新增的吊销/挂起条目 */
    @GetMapping("/{companyUscc}/crl/delta")
    public String companyCrlDelta(@PathVariable String companyUscc) {
        requireCompanyExists(companyUscc);
        return crlService.buildDeltaCrl(companyUscc).pem();
    }

    /** 公司域 OCSP：POST 请求（该公司 SUB_CA 签响应；SUSPENDED/CLOSED 公司历史证书仍必须可查，只读不门禁） */
    @PostMapping(value = "/{companyUscc}/ocsp", consumes = "application/ocsp-request",
            produces = "application/ocsp-response")
    public ResponseEntity<byte[]> companyOcspPost(@PathVariable String companyUscc, @RequestBody byte[] body) {
        requireCompanyExists(companyUscc);
        byte[] resp = ocspService.respond(companyUscc, body);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/ocsp-response")).body(resp);
    }

    /** 公司域 OCSP：GET 请求（RFC 6960 base64url 编码的 DER） */
    @GetMapping(value = "/{companyUscc}/ocsp/{b64url}", produces = "application/ocsp-response")
    public ResponseEntity<byte[]> companyOcspGet(@PathVariable String companyUscc, @PathVariable String b64url) {
        requireCompanyExists(companyUscc);
        byte[] req = Base64.getUrlDecoder().decode(b64url);
        byte[] resp = ocspService.respond(companyUscc, req);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/ocsp-response")).body(resp);
    }

    /**
     * 公司存在性校验：不存在/标识非法按现有异常口径抛 IllegalArgumentException（全局异常处理回 4xx）。
     * 注意：只校验"存在"，不校验 ACTIVE——SUSPENDED/CLOSED 公司的历史证书验证链路（root/crl/ocsp/verify）
     * 必须保持可用，这是吊销信息持续可得的合规底线；公司状态门禁只落在签发侧。
     */
    private void requireCompanyExists(String companyUscc) {
        if (companyService.findByUscc(companyUscc) == null) {
            throw new IllegalArgumentException("公司不存在: " + companyUscc);
        }
    }
}
