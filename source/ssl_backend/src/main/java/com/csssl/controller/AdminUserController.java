package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.UserCreateDTO;
import com.csssl.dto.UserPageQueryDTO;
import com.csssl.dto.UserUpdateDTO;
import com.csssl.entity.CaTenant;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.AuditService;
import com.csssl.service.AuthService;
import com.csssl.service.TenantService;
import com.csssl.enums.RoleCodeEnum;
import com.csssl.util.TenantScope;
import cn.hutool.crypto.digest.BCrypt;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理用户与角色（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 * 仅系统管理员（ADMIN）可操作；用于拆分 CA操作员/审核员/审计员 账号。
 *
 * <p>账号治理：口令由调用方必传并按复杂度校验（不再下发默认弱口令、不回显明文）；
 * 建号/改角色/启停用/重置口令均落审计，安全相关变更递增 token_version 吊销既有会话。</p>
 *
 * <p>路线A数据域口径（effectiveTenant，服务端强制）：
 * <ul>
 *   <li>建号：全局 ADMIN 可传 tenantKey 建租户用户（含租户 ADMIN，租户主由全局任命）或不传=全局用户；
 *       租户 ADMIN 只能建本租户用户，且不可建/改 ADMIN 角色，不可建全局用户；越域请求 403。</li>
 *   <li>列表：租户用户只见本租户；全局用户可按 ?tenantKey= 过滤，不传=全部
 *       ——这是唯一允许的跨租户"读"口（全局 ADMIN 总览），不构成写旁路。</li>
 *   <li>改角色/停用/重置口令：目标用户必须落在操作者数据域（租户 ADMIN 改别租户/全局用户→403）；
 *       审计记录带目标用户租户。</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final AuditService auditService;
    private final TenantService tenantService;

    /** 用户列表（分页 + 角色/租户筛选；租户用户强制本租户，全局用户 tenantKey 缺省=跨租户总览只读） */
    @PreAuthorize("hasAuthority('user:list')")
    @GetMapping
    public Result<Page<SysUser>> page(@Valid UserPageQueryDTO query, HttpServletRequest request) {
        // readTenantScope：租户用户锁本租户（传别租户 403）；全局用户不传=null=全部（唯一跨租户读口）
        String scope = TenantScope.readTenantScope(UserUtils.requireLoginUser().getTenantKey(), query.getTenantKey());
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (query.getRoleCode() != null && !query.getRoleCode().isBlank()) {
            qw.eq(SysUser::getRoleCode, query.getRoleCode());
        }
        if (scope != null) {
            qw.eq(SysUser::getTenantKey, scope);
        }
        qw.orderByAsc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(query.getPageNo(), query.getPageSize()), qw);
        page.getRecords().forEach(u -> u.setPassword(null));
        return Result.success("查询成功", page);
    }

    /**
     * 创建管理账号：全局 ADMIN 可建全局/各租户账号（tenantKey 显式指定，缺省=全局）；
     * 租户 ADMIN 仅可建本租户 OPERATOR/APPROVER/AUDITOR/SECURITY_ADMIN/KEY_ADMIN，
     * 不可建 ADMIN（租户主由全局 ADMIN 任命）、不可建全局用户；越域 403。
     */
    @PreAuthorize("hasAuthority('user:create')")
    @PostMapping
    public Result<SysUser> create(@Valid @RequestBody UserCreateDTO body, HttpServletRequest request) {
        String username = body.getUsername();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username.trim())) > 0) {
            throw new IllegalArgumentException("账号已存在：" + username);
        }
        String role = body.getRoleCode() == null || body.getRoleCode().isBlank()
                ? RoleCodeEnum.OPERATOR.name() : body.getRoleCode().trim().toUpperCase();
        if (!isSupportedRole(role)) {
            throw new IllegalArgumentException("不支持的角色：" + role
                    + "（仅支持 ADMIN / OPERATOR / APPROVER / AUDITOR / SECURITY_ADMIN / KEY_ADMIN）");
        }
        requireAssignableRole(role);
        LoginUser loginUser = UserUtils.requireLoginUser();
        String myTenant = loginUser.getTenantKey();
        String reqTenant = blankToNull(body.getTenantKey());
        String targetTenant;
        if (myTenant != null) {
            // 租户 ADMIN：一律落本租户；请求 tenantKey 与本人租户不一致=越域 403；不可任命租户主（ADMIN）
            targetTenant = TenantScope.effectiveTenant(myTenant, reqTenant);
            if (RoleCodeEnum.ADMIN.name().equals(role)) {
                throw new SecurityException("权限不足：租户管理员不可创建 ADMIN 角色账号（租户主由全局 ADMIN 任命）");
            }
        } else {
            // 全局 ADMIN：显式 tenantKey=建该租户用户（租户须存在）；缺省=全局用户（tenant_key NULL）
            targetTenant = reqTenant;
            if (targetTenant != null) {
                tenantService.get(targetTenant);
            }
        }
        String password = body.getPassword();
        AuthService.checkPasswordComplexity(password);
        SysUser user = new SysUser();
        user.setUsername(username.trim());
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setRealName(body.getRealName() == null || body.getRealName().isBlank()
                ? username.trim() : body.getRealName().trim());
        user.setRoleCode(role);
        user.setTenantKey(targetTenant);
        user.setStatus(1);
        user.setFailedAttempts(0);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        auditService.record(loginUser.getUsername(), "USER_CREATE", String.valueOf(user.getId()),
                "创建账号：" + user.getUsername() + "，角色=" + role + "，姓名=" + user.getRealName()
                        + "，租户=" + (targetTenant == null ? "全局" : targetTenant),
                clientIp(request), auditTenant(targetTenant, myTenant));
        user.setPassword(null);
        return Result.success("创建成功", user);
    }

    /**
     * 更新角色/姓名/启停用（password 可选重置）；口令/角色/状态变更后旧会话即刻失效。
     * 目标用户必须落在操作者数据域（租户 ADMIN 改别租户/全局用户→403）；
     * 租户 ADMIN 不可把角色改为 ADMIN（租户主任命权保留在全局 ADMIN）。
     */
    @PreAuthorize("hasAuthority('user:update')")
    @PostMapping("/{id}")
    public Result<SysUser> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO body,
                                  HttpServletRequest request) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在（id=" + id + "）");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        String operator = loginUser.getUsername();
        String myTenant = loginUser.getTenantKey();


        String ip = clientIp(request);
        String targetTenant = user.getTenantKey();
        if (myTenant != null) {
            // 租户 ADMIN：目标必须为本租户用户（全局用户 tenant_key=NULL 亦不可触碰）
            if (targetTenant == null || !myTenant.equals(targetTenant)) {
                throw new SecurityException("权限不足：不能操作其他租户或全局账号");
            }
            String newRole = blankToNull(body.getRoleCode());
            if (newRole != null && RoleCodeEnum.ADMIN.name().equals(newRole.trim().toUpperCase())) {
                throw new SecurityException("权限不足：租户管理员不可任命/变更为 ADMIN 角色（租户主由全局 ADMIN 任命）");
            }
        }

        LambdaUpdateWrapper<SysUser> uw = new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, id);
        List<String> changes = new ArrayList<>();
        boolean touched = false;
        // 会话吊销标记：角色/状态/口令任一变化都要 token_version+1
        boolean revoke = false;
        if (body.getRealName() != null && !body.getRealName().isBlank()) {
            String realName = body.getRealName().trim();
            if (!realName.equals(user.getRealName())) {
                changes.add("姓名：" + user.getRealName() + "→" + realName);
            }
            uw.set(SysUser::getRealName, realName);
            user.setRealName(realName);
            touched = true;
        }
        if (body.getRoleCode() != null && !isBlank(body.getRoleCode())) {
            String role = body.getRoleCode().trim().toUpperCase();
            if (!isSupportedRole(role)) {
                throw new IllegalArgumentException("不支持的角色：" + role);
            }
            requireAssignableRole(role);
            if (!role.equals(user.getRoleCode())) {
                changes.add("角色：" + user.getRoleCode() + "→" + role);
                revoke = true;
            }
            uw.set(SysUser::getRoleCode, role);
            user.setRoleCode(role);
            touched = true;
        }
        if (body.getStatus() != null) {
            int status = "0".equals(body.getStatus()) || "false".equalsIgnoreCase(body.getStatus()) ? 0 : 1;
            if (user.getStatus() == null || user.getStatus() != status) {
                changes.add("状态：" + user.getStatus() + "→" + status + "（" + (status == 1 ? "启用" : "停用") + "）");
                revoke = true;
            }
            uw.set(SysUser::getStatus, status);
            user.setStatus(status);
            touched = true;
        }
        boolean resetPassword = body.getPassword() != null && !isBlank(body.getPassword());
        if (resetPassword) {
            // 重置口令按同一复杂度校验；顺带解锁，避免管理员重置后仍被锁定挡住
            AuthService.checkPasswordComplexity(body.getPassword());
            uw.set(SysUser::getPassword, BCrypt.hashpw(body.getPassword(), BCrypt.gensalt()))
                    .set(SysUser::getFailedAttempts, 0)
                    .set(SysUser::getLockedUntil, null);
            revoke = true;
            touched = true;
        }
        if (revoke) {
            AuthService.bumpTokenVersion(uw);
        }
        if (touched) {
            userMapper.update(null, uw);
        }
        // 审计归位：按目标用户租户（全局用户=平台主租户留痕）
        String auditTenant = auditTenant(targetTenant, myTenant);
        if (!changes.isEmpty()) {
            auditService.record(operator, "USER_UPDATE", String.valueOf(id),
                    "更新账号 " + user.getUsername() + "：" + String.join("；", changes), ip, auditTenant);
        }
        if (resetPassword) {
            auditService.record(operator, "USER_RESET_PWD", String.valueOf(id),
                    "重置账号 " + user.getUsername() + " 的登录口令（口令不入库留痕、需该用户重新登录）", ip, auditTenant);
        }
        user.setPassword(null);
        return Result.success("更新成功", user);
    }

    /** 审计租户归位：目标账号租户优先；全局账号（tenant_key NULL）按操作者域，操作者亦全局则 default */
    private static String auditTenant(String targetUserTenant, String operatorTenant) {
        if (targetUserTenant != null && !targetUserTenant.isBlank()) {
            return targetUserTenant;
        }
        return operatorTenant == null || operatorTenant.isBlank() ? CaTenant.PRIMARY_TENANT_KEY : operatorTenant;
    }

    private boolean isSupportedRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim().toUpperCase();
        // 预置角色（枚举）或库内已存在的自定义角色均可分配；是否启用由 requireAssignableRole 把关
        if (RoleCodeEnum.fromCode(r) != null) {
            return true;
        }
        return roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, r)) > 0;
    }

    /** 建号/改角色时校验：角色档案存在且已启用（角色配置页停用后不得再分配） */
    private SysRole requireAssignableRole(String roleCode) {
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在：" + roleCode);
        }
        if (role.getStatus() == null || role.getStatus() != 1) {
            throw new IllegalArgumentException("角色已停用，不能分配：" + role.getRoleName() + "（" + roleCode + "）");
        }
        return role;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
