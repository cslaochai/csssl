package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.Result;
import com.csssl.vo.MyPermsVO;
import com.csssl.vo.PermItemVO;
import com.csssl.dto.RolePermsSaveDTO;
import com.csssl.vo.RolePermsVO;
import com.csssl.vo.RoleSavePermsVO;
import com.csssl.dto.RoleUpdateDTO;
import com.csssl.vo.RoleDetailVO;
import com.csssl.vo.RoleVo;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    public Result<List<RoleVo>> list() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .orderByAsc(SysRole::getSortOrder).orderByAsc(SysRole::getId));
        List<RoleVo> rows = new ArrayList<>();
        for (SysRole role : roles) {
            Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRoleCode, role.getRoleCode()));
            RoleVo vo = new RoleVo();
            vo.setId(role.getId());
            vo.setRoleCode(role.getRoleCode());
            vo.setRoleName(role.getRoleName());
            vo.setRoleDesc(role.getRoleDesc());
            vo.setSortOrder(role.getSortOrder());
            vo.setStatus(role.getStatus());
            vo.setBuiltin(role.getBuiltin());
            vo.setCreatedAt(role.getCreatedAt());
            vo.setUserCount(userCount == null ? 0 : userCount);
            vo.setPermCount(permissionService.configured(role.getRoleCode()).size());
            vo.setMaxPermCount(PermissionCatalog.statutoryMax(role.getRoleCode()).size());
            rows.add(vo);
        }
        return Result.success("查询成功", rows);
    }

    /** 当前登录人角色与生效权限（登录后自刷新用；前端据此重建侧栏与按钮） */
    @GetMapping("/my-perms")
    public Result<MyPermsVO> myPerms(HttpServletRequest request) {
        String roleCode = RoleCode.currentRole(request);
        SysRole role = findByCode(roleCode);
        MyPermsVO vo = new MyPermsVO();
        vo.setRoleCode(roleCode);
        vo.setRoleName(role == null ? roleCode : role.getRoleName());
        vo.setRoleStatus(role == null ? 1 : role.getStatus());
        vo.setPerms(permissionService.effectiveList(roleCode));
        return Result.success("查询成功", vo);
    }

    /**
     * 角色权限详情：已配置权限点 + 全量权限目录（含是否在该角色法定边界内）。
     * 供角色配置页权限树回显与置灰渲染，一次请求拿全，避免前端再拼目录。
     */
    @GetMapping("/{roleCode}/perms")
    public Result<RolePermsVO> rolePerms(@PathVariable String roleCode, HttpServletRequest request) {
        requireGlobalActor(request);
        permissionService.require(request, PermissionCatalog.ROLE_PERM);
        SysRole role = requireRole(roleCode);
        List<PermItemVO> catalog = new ArrayList<>();
        for (PermissionCatalog.PermDef def : PermissionCatalog.all()) {
            PermItemVO item = new PermItemVO();
            item.setCode(def.code());
            item.setName(def.name());
            item.setType(def.type());
            item.setGroup(def.group());
            item.setNote(def.note());
            item.setAllowed(PermissionCatalog.allowedFor(role.getRoleCode(), def.code()));
            catalog.add(item);
        }
        RolePermsVO vo = new RolePermsVO();
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setRoleDesc(role.getRoleDesc());
        vo.setBuiltin(role.getBuiltin());
        vo.setConfigured(permissionService.configured(role.getRoleCode()));
        vo.setCatalog(catalog);
        return Result.success("查询成功", vo);
    }

    /**
     * 更新角色档案（名称/职责/排序/启停）。角色编码不可改；系统管理员不可停用（防自锁）。
     * 启停变更后失效权限缓存，停用角色账号下次请求即被拦截器拒绝登录。
     */
    @PostMapping("/{roleCode}")
    public Result<RoleDetailVO> update(@PathVariable String roleCode,
                                       @Valid @RequestBody RoleUpdateDTO body,
                                       HttpServletRequest request) {
        requireGlobalActor(request);
        permissionService.require(request, PermissionCatalog.ROLE_UPDATE);
        SysRole role = requireRole(roleCode);

        List<String> changes = new ArrayList<>();
        String name = body.getRoleName();
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
        if (body.getRoleDesc() != null) {
            String desc = body.getRoleDesc() == null ? "" : body.getRoleDesc().trim();
            if (desc.length() > 500) {
                throw new IllegalArgumentException("职责说明过长（最多 500 字）");
            }
            if (!desc.equals(role.getRoleDesc() == null ? "" : role.getRoleDesc())) {
                changes.add("职责说明已更新");
            }
            role.setRoleDesc(desc);
        }
        if (body.getSortOrder() != null && !body.getSortOrder().isBlank()) {
            int sort;
            try {
                sort = Integer.parseInt(body.getSortOrder().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("排序号必须为整数");
            }
            if (role.getSortOrder() == null || role.getSortOrder() != sort) {
                changes.add("排序：" + role.getSortOrder() + "→" + sort);
            }
            role.setSortOrder(sort);
        }
        if (body.getStatus() != null && !body.getStatus().isBlank()) {
            int status = "0".equals(body.getStatus().trim()) || "false".equalsIgnoreCase(body.getStatus().trim()) ? 0 : 1;
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
                    clientIp(request), CaOrg.PRIMARY_ORG_KEY);
        }
        RoleDetailVO vo = new RoleDetailVO();
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setStatus(role.getStatus());
        return Result.success("角色已更新", vo);
    }

    /**
     * 保存角色权限（菜单+按钮，整表替换）：逐项校验是否存在、是否在法定边界内，变更落审计。
     * body {"perms":["menu:certs","cert:issue", ...]}
     */
    @PostMapping("/{roleCode}/perms")
    public Result<RoleSavePermsVO> savePerms(@PathVariable String roleCode,
                                             @Valid @RequestBody RolePermsSaveDTO saveDTO,
                                             HttpServletRequest request) {
        requireGlobalActor(request);
        permissionService.require(request, PermissionCatalog.ROLE_PERM);
        SysRole role = requireRole(roleCode);
        permissionService.save(role.getRoleCode(), saveDTO.getPerms(),
                RoleCode.currentUser(request), clientIp(request), CaOrg.PRIMARY_ORG_KEY);
        RoleSavePermsVO vo = new RoleSavePermsVO();
        vo.setRoleCode(role.getRoleCode());
        vo.setPerms(permissionService.effectiveList(role.getRoleCode()));
        return Result.success("权限已保存，即时生效", vo);
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
