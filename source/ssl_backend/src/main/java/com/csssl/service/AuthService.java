package com.csssl.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.dto.LoginRequest;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysUserMapper;
import com.csssl.util.JwtUtil;
import com.csssl.util.Sm2Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录鉴权：SM2 解密口令 → BCrypt 校验 → JWT 会话（对齐 cslk HIS / csmig 方案）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final Sm2Util sm2Util;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    /**
     * 账号口令登录：口令已由前端 SM2 加密（C1C3C2），后端解密后 BCrypt 校验。
     */
    public Map<String, Object> login(LoginRequest req, String ip) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        String password = sm2Util.decryptPassword(req.getPasswordCipher());

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername().trim()));
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !BCrypt.checkpw(password, user.getPassword())) {
            auditService.record(req.getUsername().trim(), "LOGIN", null, "登录失败（账号或口令错误）", ip);
            throw new IllegalArgumentException("账号或口令错误");
        }

        String token = jwtUtil.createToken(user.getUsername());
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);
        auditService.record(user.getUsername(), "LOGIN", null, "登录成功", ip);

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        return data;
    }

    /** 退出登录：仅记录审计，JWT 无状态，客户端丢弃令牌即可 */
    public void logout(String username, String ip) {
        auditService.record(username, "LOGOUT", null, "退出登录", ip);
    }
}
