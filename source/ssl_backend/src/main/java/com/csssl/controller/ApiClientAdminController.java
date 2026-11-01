package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.ApiClientCreateDTO;
import com.csssl.entity.LoginUser;
import com.csssl.service.ApiClientService;
import com.csssl.util.UserUtils;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.ApiClientVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 外部系统 API 客户端管理（仅全局账号）：登记/轮换/启停，secret 仅登记与轮换响应展示一次。
 *
 * <p>接入凭证归租户管理员（apiclient:manage），系统管理员只读巡检、不得登记或轮换；
 * 客户端绑定的租户在登记时确定，外部请求 URL 不携带租户标识。</p>
 */
@RestController
@RequestMapping("/admin/api-clients")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('apiclient:manage')")
public class ApiClientAdminController {

    private final ApiClientService clientService;

    /**
     * 客户端列表（不返回 secret）
     */
    @GetMapping
    public Result<List<ApiClientVO>> list() {
        requireGlobalAdmin();
        return Result.success("查询成功", clientService.list());
    }

    /**
     * 登记客户端：返回一次性 secret，调用方须立即妥善保存
     */
    @PostMapping
    public Result<ApiClientSecretVO> create(@Valid @RequestBody ApiClientCreateDTO body,
                                            HttpServletRequest request) {
        LoginUser user = requireGlobalAdmin();
        return Result.success("登记成功，请立即保存 secret（仅展示一次）",
                clientService.create(body.getTenantKey(), body.getClientName(), body.getIpAllowlist(),
                        body.getClientCertSha256(), user.getUsername(), clientIp(request)));
    }

    /**
     * 轮换 secret：旧 secret 立即失效
     */
    @PostMapping("/{id}/rotate")
    public Result<ApiClientSecretVO> rotate(@PathVariable Long id, HttpServletRequest request) {
        LoginUser user = requireGlobalAdmin();
        return Result.success("已轮换，请立即保存新 secret（仅展示一次）",
                clientService.rotate(id, user.getUsername(), clientIp(request)));
    }

    /**
     * 启停客户端
     */
    @PostMapping("/{id}/status")
    public Result<ApiClientVO> status(@PathVariable Long id, @RequestParam boolean enabled,
                                      HttpServletRequest request) {
        LoginUser user = requireGlobalAdmin();
        return Result.success(enabled ? "已启用" : "已停用",
                clientService.setStatus(id, enabled, user.getUsername(), clientIp(request)));
    }

    private LoginUser requireGlobalAdmin() {
        LoginUser user = UserUtils.requireLoginUser();
        if (user.getTenantKey() != null) {
            throw new SecurityException("权限不足：API 客户端管理仅限全局系统管理员");
        }
        return user;
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
