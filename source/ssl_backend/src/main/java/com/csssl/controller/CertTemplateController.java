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
 * 证书模板接口（GM/T 0034 §5.3.4）：查询需登录，增删改仅系统管理员。
 */
@RestController
@RequestMapping("/admin/templates")
@RequiredArgsConstructor
public class CertTemplateController {

    private final CertTemplateService templateService;

    /** 模板列表（分页 + 关键字） */
    @GetMapping
    public Result<Page<CertTemplate>> page(@RequestParam(defaultValue = "1") long pageNo,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword) {
        return Result.success("查询成功", templateService.page(pageNo, pageSize, keyword));
    }

    /** 模板详情 */
    @GetMapping("/{id}")
    public Result<CertTemplate> detail(@PathVariable Long id) {
        return Result.success("查询成功", templateService.detail(id));
    }

    /** 创建模板（仅系统管理员） */
    @PostMapping
    public Result<CertTemplate> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        return Result.success("模板创建成功", templateService.create(body));
    }

    /** 更新模板（仅系统管理员） */
    @PostMapping("/{id}")
    public Result<CertTemplate> update(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        return Result.success("模板更新成功", templateService.update(id, body));
    }

    /** 删除模板（仅系统管理员；内置模板不可删） */
    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        templateService.delete(id);
        return Result.success("模板已删除", null);
    }
}
