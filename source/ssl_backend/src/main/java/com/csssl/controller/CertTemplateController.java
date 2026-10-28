package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.entity.CertTemplate;
import com.csssl.service.CertTemplateService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 证书模板接口（GM/T 0034 §5.3.4）：查询需登录，增删改仅安全管理员/系统管理员
 * （证书策略管理职责从 OPERATOR 剥离，对应六类角色中的安全管理员）。
 *
 * <p>路线A数据域：列表/详情按可见集过滤（全局模板 org_key NULL + 本机构模板）；
 * 机构 SECURITY_ADMIN 创建/修改/删除仅限本机构模板；全局 ADMIN/SECURITY_ADMIN 可管全局模板
 * 与各机构模板——模板域用 ?orgKey=（或 body.orgKey）指定，建全局模板 orgKey 传空=全局；
 * 机构用户显式请求别机构域=403（effectiveOrg 口径）。</p>
 */
@RestController
@RequestMapping("/admin/templates")
@RequiredArgsConstructor
public class CertTemplateController {

    private final CertTemplateService templateService;

    /** 模板列表（分页 + 关键字；机构用户=全局+本机构可见集，全局用户 orgKey 缺省=全量总览） */
    @GetMapping
    public Result<Page<CertTemplate>> page(@RequestParam(defaultValue = "1") long pageNo,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String orgKey,
                                           HttpServletRequest request) {
        return Result.success("查询成功", templateService.page(pageNo, pageSize, keyword,
                RoleCode.readOrgScope(request, orgKey)));
    }

    /** 模板详情（按可见集过滤：机构用户访问他机构模板按不存在处理） */
    @GetMapping("/{id}")
    public Result<CertTemplate> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("查询成功", templateService.detailVisible(id, RoleCode.currentOrg(request)));
    }

    /**
     * 创建模板（仅安全管理员/系统管理员；变更落审计）。
     * 全局操作者：body.orgKey 空=建全局模板，非空=为指定机构建模板；机构操作者：锁本机构，越域 403。
     */
    @PostMapping
    public Result<CertTemplate> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.SECURITY_ADMIN);
        String myOrg = RoleCode.currentOrg(request);
        String reqOrg = body.get("orgKey");
        String tplOrg = myOrg != null && !myOrg.isBlank()
                ? RoleCode.effectiveOrg(request, reqOrg)
                : (reqOrg == null || reqOrg.isBlank() ? null : reqOrg.trim());
        return Result.success("模板创建成功",
                templateService.create(body, RoleCode.currentUser(request), clientIp(request), tplOrg, myOrg));
    }

    /** 更新模板（仅安全管理员/系统管理员；变更落审计；机构操作者仅限本机构模板） */
    @PostMapping("/{id}")
    public Result<CertTemplate> update(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.SECURITY_ADMIN);
        return Result.success("模板更新成功",
                templateService.update(id, body, RoleCode.currentUser(request), clientIp(request),
                        RoleCode.currentOrg(request)));
    }

    /** 删除模板（仅安全管理员/系统管理员；内置模板不可删；变更落审计；机构操作者仅限本机构模板） */
    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN, RoleCode.SECURITY_ADMIN);
        templateService.delete(id, RoleCode.currentUser(request), clientIp(request),
                RoleCode.currentOrg(request));
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
