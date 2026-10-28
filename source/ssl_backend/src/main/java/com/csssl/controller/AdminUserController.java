package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.AuditService;
import com.csssl.service.AuthService;
import com.csssl.util.RoleCode;
import cn.hutool.crypto.digest.BCrypt;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理用户与角色（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 * 仅系统管理员（ADMIN）可操作；用于拆分 CA操作员/审核员/审计员 账号。
 *
 * <p>账号治理：口令由调用方必传并按复杂度校验（不再下发默认弱口令、不回显明文）；
 * 建号/改角色/启停用/重置口令均落审计，安全相关变更递增 token_version 吊销既有会话。</p>
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final SysUserMapper userMapper;
    private final AuditService auditService;

    /** 用户列表（分页 + 角色筛选） */
    @GetMapping
    public Result<Page<SysUser>> page(@RequestParam(defaultValue = "1") long pageNo,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String roleCode,
                                      HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (roleCode != null && !roleCode.isBlank()) {
            qw.eq(SysUser::getRoleCode, roleCode);
        }
        qw.orderByAsc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        page.getRecords().forEach(u -> u.setPassword(null));
        return Result.success("查询成功", page);
    }

    /** 创建管理账号（admin/操作员/审核员/审计员）：口令必传且满足复杂度，服务端不落默认口令 */
    @PostMapping
    public Result<SysUser> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username.trim())) > 0) {
            throw new IllegalArgumentException("账号已存在：" + username);
        }
        String role = body.get("roleCode") == null || body.get("roleCode").isBlank()
                ? RoleCode.OPERATOR : body.get("roleCode").trim().toUpperCase();
        if (!isSupportedRole(role)) {
            throw new IllegalArgumentException("不支持的角色：" + role
                    + "（仅支持 ADMIN / OPERATOR / APPROVER / AUDITOR / SECURITY_ADMIN / KEY_ADMIN）");
        }
        String password = body.get("password");
        AuthService.checkPasswordComplexity(password);
        SysUser user = new SysUser();
        user.setUsername(username.trim());
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setRealName(body.get("realName") == null || body.get("realName").isBlank()
                ? username.trim() : body.get("realName").trim());
        user.setRoleCode(role);
        user.setStatus(1);
        user.setFailedAttempts(0);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        auditService.record(RoleCode.currentUser(request), "USER_CREATE", String.valueOf(user.getId()),
                "创建账号：" + user.getUsername() + "，角色=" + role + "，姓名=" + user.getRealName(),
                clientIp(request));
        user.setPassword(null);
        return Result.success("创建成功", user);
    }

    /** 更新角色/姓名/启停用（password 可选重置）；口令/角色/状态变更后旧会话即刻失效 */
    @PostMapping("/{id}")
    public Result<SysUser> update(@PathVariable Long id, @RequestBody Map<String, String> body,
                                  HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在（id=" + id + "）");
        }
        String operator = RoleCode.currentUser(request);
        String ip = clientIp(request);

        LambdaUpdateWrapper<SysUser> uw = new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, id);
        List<String> changes = new ArrayList<>();
        boolean touched = false;
        // 会话吊销标记：角色/状态/口令任一变化都要 token_version+1
        boolean revoke = false;
        if (body.containsKey("realName") && body.get("realName") != null && !body.get("realName").isBlank()) {
            String realName = body.get("realName").trim();
            if (!realName.equals(user.getRealName())) {
                changes.add("姓名：" + user.getRealName() + "→" + realName);
            }
            uw.set(SysUser::getRealName, realName);
            user.setRealName(realName);
            touched = true;
        }
        if (body.containsKey("roleCode") && !isBlank(body.get("roleCode"))) {
            String role = body.get("roleCode").trim().toUpperCase();
            if (!isSupportedRole(role)) {
                throw new IllegalArgumentException("不支持的角色：" + role);
            }
            if (!role.equals(user.getRoleCode())) {
                changes.add("角色：" + user.getRoleCode() + "→" + role);
                revoke = true;
            }
            uw.set(SysUser::getRoleCode, role);
            user.setRoleCode(role);
            touched = true;
        }
        if (body.containsKey("status")) {
            int status = "0".equals(body.get("status")) || "false".equalsIgnoreCase(body.get("status")) ? 0 : 1;
            if (user.getStatus() == null || user.getStatus() != status) {
                changes.add("状态：" + user.getStatus() + "→" + status + "（" + (status == 1 ? "启用" : "停用") + "）");
                revoke = true;
            }
            uw.set(SysUser::getStatus, status);
            user.setStatus(status);
            touched = true;
        }
        boolean resetPassword = body.containsKey("password") && !isBlank(body.get("password"));
        if (resetPassword) {
            // 重置口令按同一复杂度校验；顺带解锁，避免管理员重置后仍被锁定挡住
            AuthService.checkPasswordComplexity(body.get("password"));
            uw.set(SysUser::getPassword, BCrypt.hashpw(body.get("password"), BCrypt.gensalt()))
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
        if (!changes.isEmpty()) {
            auditService.record(operator, "USER_UPDATE", String.valueOf(id),
                    "更新账号 " + user.getUsername() + "：" + String.join("；", changes), ip);
        }
        if (resetPassword) {
            auditService.record(operator, "USER_RESET_PWD", String.valueOf(id),
                    "重置账号 " + user.getUsername() + " 的登录口令（口令不入库留痕、需该用户重新登录）", ip);
        }
        user.setPassword(null);
        return Result.success("更新成功", user);
    }

    private boolean isSupportedRole(String role) {
        return RoleCode.ADMIN.equals(role) || RoleCode.OPERATOR.equals(role)
                || RoleCode.APPROVER.equals(role) || RoleCode.AUDITOR.equals(role)
                || RoleCode.SECURITY_ADMIN.equals(role) || RoleCode.KEY_ADMIN.equals(role);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
