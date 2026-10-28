package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.Result;
import com.csssl.entity.CaOrg;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.AuditService;
import com.csssl.service.PermissionService;
import com.csssl.util.PermissionCatalog;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色配置接口（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 *
 * <p>角色集合为六类法定角色（编码不可改、不可增删），本接口只负责：</p>
 * <ul>
 *   <li>角色档案维护（名称/职责/排序/启停）；</li>
 *   <li>角色权限授权（菜单 + 按钮），只能在该角色法定职责边界内收窄，越界服务端拒绝；</li>
 *   <li>当前登录人权限下发（登录响应之外的自刷新入口）。</li>
 * </ul>
 *
 * <p>权限口径：列表/下拉登录即可（账号管理页要用角色下拉）；档案修改需 role:update，
 * 权限查看与保存需 role:perm；均只授系统管理员（PermissionCatalog 法定上限固化）。</p>
 */
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** 角色列表（含账号数、已授权数；供角色配置页与账号页角色下拉） */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .orderByAsc(SysRole::getSortOrder).orderByAsc(SysRole::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysRole role : roles) {
            Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRoleCode, role.getRoleCode()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", role.getId());
            row.put("roleCode", role.getRoleCode());
            row.put("roleName", role.getRoleName());
            row.put("roleDesc", role.getRoleDesc());
            row.put("sortOrder", role.getSortOrder());
            row.put("status", role.getStatus());
            row.put("builtin", role.getBuiltin());
            row.put("createdAt", role.getCreatedAt());
            row.put("userCount", userCount == null ? 0 : userCount);
            row.put("permCount", permissionService.configured(role.getRoleCode()).size());
            row.put("maxPermCount", PermissionCatalog.statutoryMax(role.getRoleCode()).size());
            rows.add(row);
        }
        return Result.success("查询成功", rows);
    }

    /** 当前登录人角色与生效权限（登录后自刷新用；前端据此重建侧栏与按钮） */
    @GetMapping("/my-perms")
    public Result<Map<String, Object>> myPerms(HttpServletRequest request) {
        String roleCode = RoleCode.currentRole(request);
        SysRole role = findByCode(roleCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleCode", roleCode);
        data.put("roleName", role == null ? roleCode : role.getRoleName());
        data.put("roleStatus", role == null ? 1 : role.getStatus());
        data.put("perms", permissionService.effectiveList(roleCode));
        return Result.success("查询成功", data);
    }

    /**
     * 角色权限详情：已配置权限点 + 全量权限目录（含是否在该角色法定边界内）。
     * 供角色配置页权限树回显与置灰渲染，一次请求拿全，避免前端再拼目录。
     */
    @GetMapping("/{roleCode}/perms")
    public Result<Map<String, Object>> rolePerms(@PathVariable String roleCode, HttpServletRequest request) {
        requireGlobalActor(request);
        permissionService.require(request, PermissionCatalog.ROLE_PERM);
        SysRole role = requireRole(roleCode);
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (PermissionCatalog.PermDef def : PermissionCatalog.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", def.code());
            item.put("name", def.name());
            item.put("type", def.type());
            item.put("group", def.group());
            item.put("note", def.note());
            item.put("allowed", PermissionCatalog.allowedFor(role.getRoleCode(), def.code()));
            catalog.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleCode", role.getRoleCode());
        data.put("roleName", role.getRoleName());
        data.put("roleDesc", role.getRoleDesc());
        data.put("builtin", role.getBuiltin());
        data.put("configured", permissionService.configured(role.getRoleCode()));
        data.put("catalog", catalog);
        return Result.success("查询成功", data);
    }

    /**
     * 更新角色档案（名称/职责/排序/启停）。角色编码不可改；系统管理员不可停用（防自锁）。
     * 启停变更后失效权限缓存，停用角色账号下次请求即被拦截器拒绝登录。
     */
    @PostMapping("/{roleCode}")
    public Result<Map<String, Object>> update(@PathVariable String roleCode,
                                              @RequestBody Map<String, String> body,
                                              HttpServletRequest request) {
        requireGlobalActor(request);
        permissionService.require(request, PermissionCatalog.ROLE_UPDATE);
        SysRole role = requireRole(roleCode);

        List<String> changes = new ArrayList<>();
        String name = body.get("roleName");
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            if (trimmed.length() > 64) {
                throw new IllegalArgumentException("角色名称过长（最多 64 字）");
            }
            if (!trimmed.equals(role.getRoleName())) {
                changes.add("名称：" + role.getRoleName() + "→" + trimmed);
            }
            role.setRoleName(trimmed);
        }
        if (body.containsKey("roleDesc")) {
            String desc = body.get("roleDesc") == null ? "" : body.get("roleDesc").trim();
            if (desc.length() > 500) {
                throw new IllegalArgumentException("职责说明过长（最多 500 字）");
            }
            if (!desc.equals(role.getRoleDesc() == null ? "" : role.getRoleDesc())) {
                changes.add("职责说明已更新");
            }
            role.setRoleDesc(desc);
        }
        if (body.containsKey("sortOrder") && body.get("sortOrder") != null && !body.get("sortOrder").isBlank()) {
            int sort;
            try {
                sort = Integer.parseInt(body.get("sortOrder").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("排序号必须为整数");
            }
            if (role.getSortOrder() == null || role.getSortOrder() != sort) {
                changes.add("排序：" + role.getSortOrder() + "→" + sort);
            }
            role.setSortOrder(sort);
        }
        if (body.containsKey("status") && body.get("status") != null && !body.get("status").isBlank()) {
            int status = "0".equals(body.get("status").trim()) || "false".equalsIgnoreCase(body.get("status").trim()) ? 0 : 1;
            if (RoleCode.ADMIN.equals(role.getRoleCode()) && status == 0) {
                throw new IllegalArgumentException("系统管理员角色不可停用（避免无人可管理角色与账号）");
            }
            if (role.getStatus() == null || role.getStatus() != status) {
                changes.add("状态：" + (role.getStatus() == null ? "" : role.getStatus()) + "→" + status
                        + "（" + (status == 1 ? "启用" : "停用") + "）");
            }
            role.setStatus(status);
        }
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);
        permissionService.invalidate(role.getRoleCode());
        if (!changes.isEmpty()) {
            auditService.record(RoleCode.currentUser(request), "ROLE_UPDATE", role.getRoleCode(),
                    "更新角色 " + role.getRoleCode() + "：" + String.join("；", changes),
                    clientIp(request), CaOrg.DEFAULT_ORG_KEY);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleCode", role.getRoleCode());
        data.put("roleName", role.getRoleName());
        data.put("status", role.getStatus());
        return Result.success("角色已更新", data);
    }

    /**
     * 保存角色权限（菜单+按钮，整表替换）：逐项校验是否存在、是否在法定边界内，变更落审计。
     * body {"perms":["menu:certs","cert:issue", ...]}
     */
    @PostMapping("/{roleCode}/perms")
    public Result<Map<String, Object>> savePerms(@PathVariable String roleCode,
                                                 @RequestBody Map<String, List<String>> body,
                                                 HttpServletRequest request) {
        requireGlobalActor(request);
        permissionService.require(request, PermissionCatalog.ROLE_PERM);
        SysRole role = requireRole(roleCode);
        permissionService.save(role.getRoleCode(), body == null ? null : body.get("perms"),
                RoleCode.currentUser(request), clientIp(request), CaOrg.DEFAULT_ORG_KEY);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleCode", role.getRoleCode());
        data.put("perms", permissionService.effectiveList(role.getRoleCode()));
        return Result.success("权限已保存，即时生效", data);
    }

    /** 角色配置属全局管理动作：机构管理员（org_key 非空）一律拒绝，防止单机构改动影响全院分权口径 */
    private void requireGlobalActor(HttpServletRequest request) {
        String org = RoleCode.currentOrg(request);
        if (org != null && !org.isBlank()) {
            throw new SecurityException("权限不足：角色配置属全局管理动作，仅全局系统管理员可操作");
        }
    }

    /** 角色必须存在（六类法定角色，未知编码按参数错误处理） */
    private SysRole requireRole(String roleCode) {
        SysRole role = findByCode(roleCode);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在：" + roleCode);
        }
        return role;
    }

    private SysRole findByCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        return roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode.trim().toUpperCase()));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
