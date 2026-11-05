package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.ApiClientCreateDTO;
import com.csssl.dto.BoolIdDTO;
import com.csssl.dto.IdDTO;
import com.csssl.entity.LoginUser;
import com.csssl.service.ApiClientService;
import com.csssl.util.UserUtils;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.ApiClientVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 外部系统 API 客户端管理（仅全局账号）：登记/轮换/启停，secret 仅登记与轮换响应展示一次。
 *
 * <p>接入凭证归公司管理员（apiclient:manage），系统管理员只读巡检、不得登记或轮换；
 * 客户端绑定的公司在登记时确定，外部请求 URL 不携带公司标识。</p>
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
    @PostMapping("/list")
    public Result<List<ApiClientVO>> list() {
        requireGlobalAdmin();
        return Result.success("查询成功", clientService.list());
    }

    /**
     * 登记客户端：返回一次性 secret，调用方须立即妥善保存
     */
    @PostMapping("/create")
    public Result<ApiClientSecretVO> create(@Valid @RequestBody ApiClientCreateDTO body,
                                            HttpServletRequest request) {
        LoginUser user = requireGlobalAdmin();
        return Result.success("登记成功，请立即保存 secret（仅展示一次）",
                clientService.create(body.getCompanyUscc(), body.getClientName(), body.getSystemName(),
                        body.getIpAllowlist(), body.getClientCertSha256(), user, clientIp(request)));
    }

    /**
     * 轮换 secret：旧 secret 立即失效
     */
    @PostMapping("/rotate")
    public Result<ApiClientSecretVO> rotate(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser user = requireGlobalAdmin();
        return Result.success("已轮换，请立即保存新 secret（仅展示一次）",
                clientService.rotate(body.getId(), user, clientIp(request)));
    }

    /**
     * 启停客户端
     */
    @PostMapping("/status")
    public Result<ApiClientVO> status(@Valid @RequestBody BoolIdDTO body,
                                      HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        if (body.getEnabled() == null) {
            throw new IllegalArgumentException("参数 enabled 不能为空");
        }
        LoginUser user = requireGlobalAdmin();
        return Result.success(body.getEnabled() ? "已启用" : "已停用",
                clientService.setStatus(body.getId(), body.getEnabled(), user, clientIp(request)));
    }

    private LoginUser requireGlobalAdmin() {
        // 单租户化后账号不再携带公司域，持权管理员均视为全局管理员
        return UserUtils.requireLoginUser();
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
