package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.TemplateCreateDTO;
import com.csssl.dto.TemplatePageQueryDTO;
import com.csssl.dto.TemplateUpdateDTO;
import com.csssl.dto.IdDTO;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.LoginUser;
import com.csssl.service.CertTemplateService;
import com.csssl.util.CompanyScope;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证书模板接口（GM/T 0034 §5.3.4）：查询需登录，增删改仅安全管理员/系统管理员
 * （证书策略管理职责从 OPERATOR 剥离，对应预置角色中的安全管理员）。
 *
 * <p>路线A数据域：列表/详情按可见集过滤（全局模板 company_uscc NULL + 本公司模板）；
 * 公司 SECURITY_ADMIN 创建/修改/删除仅限本公司模板；全局 ADMIN/SECURITY_ADMIN 可管全局模板
 * 与各公司模板——模板域用 body.companyUscc=（或 body.companyUscc）指定，建全局模板 companyUscc 传空=全局；
 * 公司用户显式请求别公司域=403（effectiveCompany 口径）。</p>
 */
@RestController
@RequestMapping("/admin/templates")
@RequiredArgsConstructor
public class CertTemplateController {

    private final CertTemplateService templateService;

    /**
     * 模板列表（分页 + 关键字；公司用户=全局+本公司可见集，全局用户 companyUscc 缺省=全量总览）
     */
    @PostMapping("/page")
    public Result<Page<CertTemplate>> page(@Valid @RequestBody TemplatePageQueryDTO query,
                                           HttpServletRequest request) {
        return Result.success("查询成功", templateService.page(query.getPageNo(), query.getPageSize(), query.getKeyword(),
                CompanyScope.readCompanyScope(query.getCompanyUscc())));
    }

    /**
     * 模板详情（按可见集过滤：公司用户访问他公司模板按不存在处理）
     */
    @PostMapping("/detail")
    public Result<CertTemplate> detail(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", templateService.detailVisible(body.getId(), null));
    }

    /**
     * 创建模板（仅安全管理员/系统管理员；变更落审计）。
     * 全局操作者：body.companyUscc 空=建全局模板，非空=为指定公司建模板；公司操作者：锁本公司，越域 403。
     */
    @PreAuthorize("hasAuthority('template:create')")
    @PostMapping("/create")
    public Result<CertTemplate> create(@Valid @RequestBody TemplateCreateDTO body, HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String myCompany = null;
        String reqCompany = body.getCompanyUscc();
        String tplCompany = reqCompany == null || reqCompany.isBlank() ? null : reqCompany.trim();
        return Result.success("模板创建成功",
                templateService.create(body, loginUser, clientIp(request), tplCompany, myCompany));
    }

    /**
     * 更新模板（仅安全管理员/系统管理员；变更落审计；公司操作者仅限本公司模板）
     */
    @PreAuthorize("hasAuthority('template:update')")
    @PostMapping("/update")
    public Result<CertTemplate> update(@Valid @RequestBody TemplateUpdateDTO body,
                                       HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("模板更新成功",
                templateService.update(body.getId(), body, loginUser, clientIp(request), null));
    }

    /**
     * 删除模板（仅安全管理员/系统管理员；内置模板不可删；变更落审计；公司操作者仅限本公司模板）
     */
    @PreAuthorize("hasAuthority('template:delete')")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        templateService.delete(body.getId(), loginUser, clientIp(request), null);
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
