package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysUserMapper;
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
import java.util.Map;

/**
 * 管理用户与角色（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡）。
 * 仅系统管理员（ADMIN）可操作；用于拆分 CA操作员/审核员/审计员 账号。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final SysUserMapper userMapper;

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
        return Result.success("查询成功", userMapper.selectPage(new Page<>(pageNo, pageSize), qw));
    }

    /** 创建管理账号（admin/操作员/审核员/审计员） */
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
            throw new IllegalArgumentException("不支持的角色：" + role + "（仅支持 ADMIN / OPERATOR / APPROVER / AUDITOR）");
        }
        String password = body.get("password") == null || body.get("password").isBlank()
                ? "123456" : body.get("password");
        SysUser user = new SysUser();
        user.setUsername(username.trim());
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setRealName(body.get("realName") == null || body.get("realName").isBlank()
                ? username.trim() : body.get("realName").trim());
        user.setRoleCode(role);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        user.setPassword(null);
        return Result.success("创建成功（初始口令：" + password + "）", user);
    }

    /** 更新角色/姓名/启停用（password 可选重置） */
    @PostMapping("/{id}")
    public Result<SysUser> update(@PathVariable Long id, @RequestBody Map<String, String> body,
                                  HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在（id=" + id + "）");
        }
        if (body.containsKey("realName") && !body.get("realName").isBlank()) {
            user.setRealName(body.get("realName").trim());
        }
        if (body.containsKey("roleCode") && !body.get("roleCode").isBlank()) {
            String role = body.get("roleCode").trim().toUpperCase();
            if (!isSupportedRole(role)) {
                throw new IllegalArgumentException("不支持的角色：" + role);
            }
            user.setRoleCode(role);
        }
        if (body.containsKey("status")) {
            user.setStatus("0".equals(body.get("status")) || "false".equalsIgnoreCase(body.get("status")) ? 0 : 1);
        }
        if (body.containsKey("password") && !body.get("password").isBlank()) {
            user.setPassword(BCrypt.hashpw(body.get("password"), BCrypt.gensalt()));
        }
        userMapper.updateById(user);
        user.setPassword(null);
        return Result.success("更新成功", user);
    }

    private boolean isSupportedRole(String role) {
        return RoleCode.ADMIN.equals(role) || RoleCode.OPERATOR.equals(role)
                || RoleCode.APPROVER.equals(role) || RoleCode.AUDITOR.equals(role);
    }
}
