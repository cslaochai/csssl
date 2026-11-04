package com.csssl.api.controller;

import com.csssl.common.Result;
import com.csssl.config.CaApiAuthFilter;
import com.csssl.dto.ApiApplicationSubmitDTO;
import com.csssl.dto.ApiCancelDTO;
import com.csssl.dto.ApiCertRevokeDTO;
import com.csssl.entity.ApiClient;
import com.csssl.service.CaApiService;
import com.csssl.vo.ApiApplicationVO;
import com.csssl.vo.ApiCertStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 对外证书申请 API v1（供 HIS / EMR 等外部系统直连）。
 *
 * <p>鉴权由 {@link CaApiAuthFilter} 完成（HMAC-SHA256 + 防重放 + 限流 + IP/证书白名单）；
 * URL 不携带公司标识——公司由 appId 反查，调用方无法指定其他公司的数据域。</p>
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
     * 提交证书申请（externalRequestId 幂等）
     */
    @PostMapping("/apply/sign")
    public Result<ApiApplicationVO> submit(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @RequestAttribute(CaApiAuthFilter.ATTR_BODY_SHA256) String bodyHash,
            @Valid @RequestBody ApiApplicationSubmitDTO body,
            HttpServletRequest request) {
        ApiApplicationVO vo = apiService.submit(client, body, bodyHash, clientIp(request));
        return Result.success(vo.isIdempotentReplay() ? "幂等重放，返回首次申请结果" : "申请提交成功", vo);
    }

    /**
     * 撤销申请（仅提交方；PENDING/APPROVED 可撤，已撤销幂等返回）。申请单号放在请求体内，URL 不暴露参数。
     */
    @PostMapping("/apply/cancel")
    public Result<ApiApplicationVO> cancel(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @Valid @RequestBody ApiCancelDTO body,
            HttpServletRequest request) {
        return Result.success("已撤销", apiService.cancel(client, body.getApplyNo(), body.getReason(),
                clientIp(request)));
    }

    /**
     * 吊销已签发证书（序列号、医生身份与原因放在请求体内）：外部系统吊销后同步 CA，避免 CA 侧证书仍有效。
     *
     * <p>一次请求只吊销一张，且必须落到指定医生：证书须是本 appId 提交的申请单签出的客户端证书，
     * 且请求携带的工号 / HIS 员工主键与申请单记录一致，否则 403。服务端证书与 SUB_CA 不受理。</p>
     */
    @PostMapping("/certificates/revoke")
    public Result<ApiCertStatusVO> revoke(
            @RequestAttribute(CaApiAuthFilter.ATTR_CLIENT) ApiClient client,
            @Valid @RequestBody ApiCertRevokeDTO body,
            HttpServletRequest request) {
        return Result.success("已吊销", apiService.revoke(client, body, clientIp(request)));
    }
}
