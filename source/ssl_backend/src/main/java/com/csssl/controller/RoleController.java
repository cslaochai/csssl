package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.Result;
import com.csssl.vo.MyPermsVO;
import com.csssl.vo.PermItemVO;
import com.csssl.dto.RolePermsSaveDTO;
import com.csssl.dto.RoleCreateDTO;
import com.csssl.vo.RolePermsVO;
import com.csssl.vo.RoleSavePermsVO;
import com.csssl.dto.RoleUpdateDTO;
import com.csssl.vo.RoleDetailVO;
import com.csssl.vo.RoleVO;
import com.csssl.entity.CaTenant;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.entity.LoginUser;
import com.csssl.entity.RolePerm;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.mapper.RolePermMapper;
import com.csssl.service.AuditService;
import com.csssl.service.PermissionService;
import com.csssl.util.PermissionCatalog;
import com.csssl.util.UserUtils;
import com.csssl.enums.RoleCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色配置接口（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 *
 * <p>EJBCA 式可配置模型：11 个预置角色由 {@link RoleCodeEnum} 枚举固化（编码不可改、不可删，
 * 授权只能在其法定上限内收窄）；同时支持后台自建自定义角色（builtin=0，授权上限为全量权限目录，
 * 由管理员自行组合）。本接口负责：</p>
 * <ul>
 *   <li>预置/自定义角色档案维护（名称/职责/排序/启停）；自定义角色可增删；</li>
 *   <li>角色权限授权（菜单 + 按钮），预置角色只能在其法定职责边界内收窄，越界服务端拒绝；</li>
 *   <li>当前登录人权限下发（登录响应之外的自刷新入口）。</li>
 * </ul>
 *
 * <p>权限口径：列表/下拉登录即可（账号管理页要用角色下拉）；档案修改/角色增删需 role:update，
 * 权限查看与保存需 role:perm；角色配置属全局管理动作，仅全局系统管理员可操作。</p>
 */
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final RolePermMapper rolePermMapper;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** 角色列表（含账号数、已授权数；供角色配置页与账号页角色下拉） */
    @GetMapping
    public Result<List<RoleVO>> list() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .orderByAsc(SysRole::getSortOrder).orderByAsc(SysRole::getId));
        List<RoleVO> rows = new ArrayList<>();
        for (SysRole role : roles) {
            Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRoleCode, role.getRoleCode()));
            RoleVO vo = new RoleVO();
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

    /**
     * 创建自定义角色（EJBCA 式可配置：预置角色为默认，后台可自建自定义角色）。
     * 自定义角色 builtin=0，授权上限为全量权限目录，初始无任何授权，由管理员在角色配置页自行组合。
     * 仅全局管理员可操作；角色编码须与预置角色不冲突、库内唯一。
     */
    @PreAuthorize("hasAuthority('role:update')")
    @PostMapping
    public Result<RoleDetailVO> create(@Valid @RequestBody RoleCreateDTO req, HttpServletRequest request) {
        requireGlobalActor();
        String code = req.getRoleCode();
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,31}$")) {
            throw new IllegalArgumentException("角色编码须为大写字母/数字/下划线、以字母开头、≤32位");
        }
        if (RoleCodeEnum.fromCode(code) != null) {
            throw new IllegalArgumentException("角色编码与预置角色冲突，不可使用：" + code);
        }
        if (findByCode(code) != null) {
            throw new IllegalArgumentException("角色编码已存在：" + code);
        }
        SysRole role = new SysRole();
        role.setRoleCode(code.toUpperCase());
        role.setRoleName(req.getRoleName() == null || req.getRoleName().isBlank() ? code : req.getRoleName().trim());
        role.setRoleDesc(req.getRoleDesc() == null ? "" : req.getRoleDesc().trim());
        int sort = 999;
        if (req.getSortOrder() != null && !req.getSortOrder().isBlank()) {
            try {
                sort = Integer.parseInt(req.getSortOrder().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("排序号必须为整数");
            }
        }
        role.setSortOrder(sort);
        role.setStatus(1);
        role.setBuiltin(0);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        auditService.record(UserUtils.requireLoginUser().getUsername(), "ROLE_CREATE", code,
                "创建自定义角色：" + code, clientIp(request), CaTenant.PRIMARY_TENANT_KEY);
        RoleDetailVO vo = new RoleDetailVO();
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setStatus(role.getStatus());
        return Result.success("角色已创建（自定义角色初始无授权，请在角色配置页分配权限）", vo);
    }

    /**
     * 删除自定义角色（仅 builtin=0 可删；该角色下仍有账号时拒绝，须先迁移/停用账号）。
     * 预置角色（builtin=1）不可删除（守住分权制衡的法定骨架）。
     */
    @PreAuthorize("hasAuthority('role:update')")
    @DeleteMapping("/{roleCode}")
    public Result<Void> delete(@PathVariable String roleCode, HttpServletRequest request) {
        requireGlobalActor();
        SysRole role = requireRole(roleCode);
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw new IllegalArgumentException("法定内置角色不可删除：" + roleCode);
        }
        Long users = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleCode, role.getRoleCode()));
        if (users != null && users > 0) {
            throw new IllegalArgumentException("该角色下仍有 " + users + " 个账号，请先迁移或停用账号后再删除");
        }
        rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleCode, role.getRoleCode()));
        roleMapper.deleteById(role.getId());
        permissionService.invalidate(role.getRoleCode());
        auditService.record(UserUtils.requireLoginUser().getUsername(), "ROLE_DELETE", role.getRoleCode(),
                "删除自定义角色：" + role.getRoleCode(), clientIp(request), CaTenant.PRIMARY_TENANT_KEY);
        return Result.success("角色已删除", null);
    }

    /** 当前登录人角色与生效权限（登录后自刷新用；前端据此重建侧栏与按钮） */
    @GetMapping("/my-perms")
    public Result<MyPermsVO> myPerms(HttpServletRequest request) {
        String roleCode = UserUtils.requireLoginUser().getRoleCode();
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
    @PreAuthorize("hasAuthority('role:perm')")
    @GetMapping("/{roleCode}/perms")
    public Result<RolePermsVO> rolePerms(@PathVariable String roleCode, HttpServletRequest request) {
        requireGlobalActor();
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
    @PreAuthorize("hasAuthority('role:update')")
    @PostMapping("/{roleCode}")
    public Result<RoleDetailVO> update(@PathVariable String roleCode,
                                       @Valid @RequestBody RoleUpdateDTO body,
                                       HttpServletRequest request) {
        requireGlobalActor();
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
            if (RoleCodeEnum.ADMIN.name().equals(role.getRoleCode()) && status == 0) {
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
            auditService.record(UserUtils.requireLoginUser().getUsername(), "ROLE_UPDATE", role.getRoleCode(),
                    "更新角色 " + role.getRoleCode() + "：" + String.join("；", changes),
                    clientIp(request), CaTenant.PRIMARY_TENANT_KEY);
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
    @PreAuthorize("hasAuthority('role:perm')")
    @PostMapping("/{roleCode}/perms")
    public Result<RoleSavePermsVO> savePerms(@PathVariable String roleCode,
                                             @Valid @RequestBody RolePermsSaveDTO saveDTO,
                                             HttpServletRequest request) {
        requireGlobalActor();
        SysRole role = requireRole(roleCode);
        permissionService.save(role.getRoleCode(), saveDTO.getPerms(),
                UserUtils.requireLoginUser().getUsername(), clientIp(request), CaTenant.PRIMARY_TENANT_KEY);
        RoleSavePermsVO vo = new RoleSavePermsVO();
        vo.setRoleCode(role.getRoleCode());
        vo.setPerms(permissionService.effectiveList(role.getRoleCode()));
        return Result.success("权限已保存，即时生效", vo);
    }

    /** 角色配置属全局管理动作：租户管理员（tenant_key 非空）一律拒绝，防止单租户改动影响全院分权口径 */
    private void requireGlobalActor() {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String tenant = loginUser.getTenantKey();
        if (tenant != null && !tenant.isBlank()) {
            throw new SecurityException("权限不足：角色配置属全局管理动作，仅全局系统管理员可操作");
        }
    }

    /** 角色必须存在（预置或自定义角色，未知编码按参数错误处理） */
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
