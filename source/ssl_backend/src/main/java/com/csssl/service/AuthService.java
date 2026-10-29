package com.csssl.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.csssl.dto.LoginRequest;
import com.csssl.entity.CaOrg;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysRoleMapper;
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
 *
 * <p>身份鉴别强化：连续口令错误锁定账号（等保要求），JWT 携带 token_version 支持会话吊销，
 * 自助改密走与登录一致的 SM2 密文通道。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 连续失败锁定阈值（等保身份鉴别：连续 5 次失败锁定） */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    /** 锁定时长（分钟） */
    private static final int LOCK_MINUTES = 15;
    /** 口令最短长度（复杂度：长度 + 字母数字两类，见 checkPasswordComplexity） */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final Sm2Util sm2Util;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;
    private final PermissionService permissionService;

    /**
     * 账号口令登录：口令已由前端 SM2 加密（C1C3C2），后端解密后 BCrypt 校验。
     */
    public Map<String, Object> login(LoginRequest req, String ip) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        String username = req.getUsername().trim();
        String password = sm2Util.decryptPassword(req.getPasswordCipher());

        SysUser user = loadByUsername(username);
        if (user == null) {
            auditService.record(username, "LOGIN", null, "登录失败（账号或口令错误）", ip, CaOrg.PRIMARY_ORG_KEY);
            throw new IllegalArgumentException("账号或口令错误");
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            auditService.record(username, "LOGIN", null, "登录失败（账号锁定中）", ip, orgOfUser(user));
            throw new IllegalArgumentException("账号已锁定，请稍后再试");
        }
        // 停用账号与口令错误同口径回显，避免账号枚举
        if (user.getStatus() == null || user.getStatus() != 1) {
            auditService.record(username, "LOGIN", null, "登录失败（账号已停用）", ip, orgOfUser(user));
            throw new IllegalArgumentException("账号或口令错误");
        }
        if (!passwordMatches(password, user)) {
            registerLoginFailure(user, now);
            auditService.record(username, "LOGIN", null, "登录失败（账号或口令错误）", ip, orgOfUser(user));
            throw new IllegalArgumentException("账号或口令错误");
        }
        // 角色停用即整类账号不可登录（口令校验通过后才判定，避免账号/角色枚举）
        SysRole role = roleOf(user.getRoleCode());
        if (role != null && (role.getStatus() == null || role.getStatus() != 1)) {
            auditService.record(username, "LOGIN", null, "登录失败（角色已停用：" + user.getRoleCode() + "）",
                    ip, orgOfUser(user));
            throw new IllegalArgumentException("账号所属角色已停用，请联系系统管理员");
        }

        String token = jwtUtil.createToken(user.getUsername(), user.getRoleCode(),
                user.getId(), user.getTokenVersion(), user.getOrgKey());
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getFailedAttempts, 0)
                .set(SysUser::getLockedUntil, null)
                .set(SysUser::getLastLoginTime, now)
                .set(SysUser::getLastLoginIp, ip));
        auditService.record(user.getUsername(), "LOGIN", null, "登录成功", ip, orgOfUser(user));

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("realName", user.getRealName());
        data.put("role", user.getRoleCode());
        // 角色名称以库中档案为准（角色配置页可改名，前端顶栏展示不再写死）
        data.put("roleName", role == null ? user.getRoleCode() : role.getRoleName());
        // 生效权限点（菜单 + 按钮）：前端据此构建侧栏、守卫与按钮显隐；服务端仍逐接口强校验
        data.put("perms", permissionService.effectiveList(user.getRoleCode()));
        // 机构数据域：null=全局账号；前端据此锁定/展示机构切换（服务端各域操作仍强制校验，不信任前端）
        data.put("orgKey", user.getOrgKey());
        // 平台主机构（各机构标识由管理员在机构管理维护；全局账号默认落在它上面）
        data.put("primaryOrg", CaOrg.PRIMARY_ORG_KEY);
        return data;
    }

    /**
     * 自助改密：校验原口令 → 校验新口令复杂度 → BCrypt 落库 → token_version+1（旧令牌即刻失效）。
     * 新旧口令均为前端 SM2 密文，与登录同通道，避免明文过网。
     *
     * @param tokenVersion 当前令牌携带的会话版本；与库中不符说明令牌已被吊销（改密/改角色/停用后）
     */
    public void changePassword(String username, Integer tokenVersion,
                               String oldPasswordCipher, String newPasswordCipher, String ip) {
        if (username == null || username.isBlank()) {
            throw new SecurityException("未登录或登录已过期");
        }
        SysUser user = loadByUsername(username.trim());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("账号不存在或已停用");
        }
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (tokenVersion == null || tokenVersion != currentVersion) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录后再修改口令");
        }
        String oldPassword = sm2Util.decryptPassword(oldPasswordCipher);
        String newPassword = sm2Util.decryptPassword(newPasswordCipher);
        if (!passwordMatches(oldPassword, user)) {
            auditService.record(user.getUsername(), "PWD_CHANGE", null, "修改口令失败（原口令错误）", ip, orgOfUser(user));
            throw new IllegalArgumentException("原口令错误");
        }
        checkPasswordComplexity(newPassword);
        if (passwordMatches(newPassword, user)) {
            throw new IllegalArgumentException("新口令不能与原口令相同");
        }
        userMapper.update(null, bumpTokenVersion(new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, user.getId()))
                .set(SysUser::getPassword, BCrypt.hashpw(newPassword, BCrypt.gensalt())));
        auditService.record(user.getUsername(), "PWD_CHANGE", null, "自助修改登录口令成功（需重新登录）", ip, orgOfUser(user));
        log.info("[账号] {} 自助修改口令，会话版本已递增", user.getUsername());
    }

    /** 退出登录：仅记录审计，JWT 无状态，客户端丢弃令牌即可（审计按用户机构归位，查不到账号记 default） */
    public void logout(String username, String ip) {
        SysUser user = username == null || username.isBlank() ? null : loadByUsername(username.trim());
        auditService.record(username, "LOGOUT", null, "退出登录", ip, orgOfUser(user));
    }

    /** 审计机构归位：账号归属机构（全局账号/无账号=平台主机构） */
    private static String orgOfUser(SysUser user) {
        return user == null || user.getOrgKey() == null || user.getOrgKey().isBlank()
                ? CaOrg.PRIMARY_ORG_KEY : user.getOrgKey();
    }

    /**
     * 口令复杂度：≥8 位且同时包含字母和数字（建号/重置/自助改密共用同一口径）。
     */
    public static void checkPasswordComplexity(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("口令不能为空");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("口令长度不足：至少需 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (password.length() > 64) {
            throw new IllegalArgumentException("口令长度超限：最多 64 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("口令需同时包含字母和数字");
        }
    }

    /** 按用户名取账号（唯一索引） */
    private SysUser loadByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    /** 取角色档案（角色表缺失时返回 null，由调用方按编码兜底展示，不阻断登录） */
    private SysRole roleOf(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        return roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode.trim().toUpperCase()));
    }

    /** BCrypt 校验：哈希列异常（历史脏数据）按校验失败处理，不外泄异常细节 */
    private boolean passwordMatches(String rawPassword, SysUser user) {
        try {
            return user.getPassword() != null && BCrypt.checkpw(rawPassword, user.getPassword());
        } catch (Exception e) {
            log.warn("[登录] 口令哈希校验异常 user={}：{}", user.getUsername(), e.getMessage());
            return false;
        }
    }

    /** 失败计数递增；达到阈值即锁定并清零计数（解锁后重新计数） */
    private void registerLoginFailure(SysUser user, LocalDateTime now) {
        int attempts = (user.getFailedAttempts() == null ? 0 : user.getFailedAttempts()) + 1;
        LambdaUpdateWrapper<SysUser> uw = new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, user.getId());
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            uw.set(SysUser::getFailedAttempts, 0)
                    .set(SysUser::getLockedUntil, now.plusMinutes(LOCK_MINUTES));
            log.warn("[登录] 连续失败 {} 次，账号锁定 {} 分钟：{}", attempts, LOCK_MINUTES, user.getUsername());
        } else {
            uw.set(SysUser::getFailedAttempts, attempts);
            log.info("[登录] 口令错误 user={} 累计失败 {} 次", user.getUsername(), attempts);
        }
        userMapper.update(null, uw);
    }

    /** 追加 token_version 原子自增（SQL 侧自增，避免并发写覆盖）；建号/改角色/停用/重置口令共用 */
    public static LambdaUpdateWrapper<SysUser> bumpTokenVersion(LambdaUpdateWrapper<SysUser> uw) {
        return uw.setSql("token_version = COALESCE(token_version, 0) + 1");
    }
}
