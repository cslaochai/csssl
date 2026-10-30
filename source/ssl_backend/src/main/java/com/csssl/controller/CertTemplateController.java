package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.TemplateCreateDTO;
import com.csssl.dto.TemplatePageQueryDTO;
import com.csssl.dto.TemplateUpdateDTO;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.LoginUser;
import com.csssl.service.CertTemplateService;
import com.csssl.util.TenantScope;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证书模板接口（GM/T 0034 §5.3.4）：查询需登录，增删改仅安全管理员/系统管理员
 * （证书策略管理职责从 OPERATOR 剥离，对应预置角色中的安全管理员）。
 *
 * <p>路线A数据域：列表/详情按可见集过滤（全局模板 tenant_key NULL + 本租户模板）；
 * 租户 SECURITY_ADMIN 创建/修改/删除仅限本租户模板；全局 ADMIN/SECURITY_ADMIN 可管全局模板
 * 与各租户模板——模板域用 ?tenantKey=（或 body.tenantKey）指定，建全局模板 tenantKey 传空=全局；
 * 租户用户显式请求别租户域=403（effectiveTenant 口径）。</p>
 */
@RestController
@RequestMapping("/admin/templates")
@RequiredArgsConstructor
public class CertTemplateController {

    private final CertTemplateService templateService;

    /** 模板列表（分页 + 关键字；租户用户=全局+本租户可见集，全局用户 tenantKey 缺省=全量总览） */
    @GetMapping
    public Result<Page<CertTemplate>> page(@Valid TemplatePageQueryDTO query,
                                           HttpServletRequest request) {
        return Result.success("查询成功", templateService.page(query.getPageNo(), query.getPageSize(), query.getKeyword(),
                TenantScope.readTenantScope(UserUtils.requireLoginUser().getTenantKey(), query.getTenantKey())));
    }

    /** 模板详情（按可见集过滤：租户用户访问他租户模板按不存在处理） */
    @GetMapping("/{id}")
    public Result<CertTemplate> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("查询成功", templateService.detailVisible(id, UserUtils.requireLoginUser().getTenantKey()));
    }

    /**
     * 创建模板（仅安全管理员/系统管理员；变更落审计）。
     * 全局操作者：body.tenantKey 空=建全局模板，非空=为指定租户建模板；租户操作者：锁本租户，越域 403。
     */
    @PreAuthorize("hasAuthority('template:create')")
    @PostMapping
    public Result<CertTemplate> create(@Valid @RequestBody TemplateCreateDTO body, HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String myTenant = loginUser.getTenantKey();
        String reqTenant = body.getTenantKey();
        String tplTenant = myTenant != null && !myTenant.isBlank()
                ? TenantScope.effectiveTenant(myTenant, reqTenant)
                : (reqTenant == null || reqTenant.isBlank() ? null : reqTenant.trim());
        return Result.success("模板创建成功",
                templateService.create(body, loginUser.getUsername(), clientIp(request), tplTenant, myTenant));
    }

    /** 更新模板（仅安全管理员/系统管理员；变更落审计；租户操作者仅限本租户模板） */
    @PreAuthorize("hasAuthority('template:update')")
    @PostMapping("/{id}")
    public Result<CertTemplate> update(@PathVariable Long id, @Valid @RequestBody TemplateUpdateDTO body,
                                       HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("模板更新成功",
                templateService.update(id, body, loginUser.getUsername(), clientIp(request),
                        loginUser.getTenantKey()));
    }

    /** 删除模板（仅安全管理员/系统管理员；内置模板不可删；变更落审计；租户操作者仅限本租户模板） */
    @PreAuthorize("hasAuthority('template:delete')")
    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        templateService.delete(id, loginUser.getUsername(), clientIp(request),
                loginUser.getTenantKey());
        return Result.success("模板已删除", null);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
