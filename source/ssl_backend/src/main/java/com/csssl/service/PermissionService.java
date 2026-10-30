package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.LoginUser;
import com.csssl.entity.RolePerm;
import com.csssl.enums.RoleCodeEnum;
import com.csssl.mapper.RolePermMapper;
import com.csssl.util.PermissionCatalog;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色权限服务：生效权限 = 角色配置 ∩ 法定上限，缓存于进程内，配置变更即时失效。
 *
 * <p>三道防线：</p>
 * <ol>
 *   <li>保存时逐项校验权限点是否存在、是否在角色法定上限内（越界直接拒绝并落审计告警）；</li>
 *   <li>读取时再与法定上限取交集——即使有人直改 ssl_role_perm 表也不能越权；</li>
 *   <li>接口校验统一走 {@link #require}，拒绝原因只写日志，响应泛化为"权限不足"，不暴露权限矩阵。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final RolePermMapper permMapper;
    private final AuditService auditService;

    /**
     * 角色生效权限缓存（roleCode → 权限点集合；空的不可变集合表示无授权）
     */
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    /**
     * 某角色当前生效权限（配置 ∩ 法定上限）
     */
    public Set<String> effective(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Collections.emptySet();
        }
        String rc = roleCode.trim().toUpperCase();
        Set<String> max = PermissionCatalog.statutoryMax(rc);
        if (max.isEmpty()) {
            return Collections.emptySet();
        }
        return cache.computeIfAbsent(rc, key -> {
            List<RolePerm> rows = permMapper.selectList(new LambdaQueryWrapper<RolePerm>()
                    .eq(RolePerm::getRoleCode, key));
            Set<String> codes = new LinkedHashSet<>();
            for (RolePerm row : rows) {
                // 读取侧再收口：库中被塞入的越界权限点一律忽略
                if (max.contains(row.getPermCode())) {
                    codes.add(row.getPermCode());
                }
            }
            return Collections.unmodifiableSet(codes);
        });
    }

    /**
     * 生效权限列表（按权限目录顺序，供登录下发/前端刷新）
     */
    public List<String> effectiveList(String roleCode) {
        Set<String> effective = effective(roleCode);
        List<String> ordered = new ArrayList<>();
        for (PermissionCatalog.PermDef def : PermissionCatalog.all()) {
            if (effective.contains(def.code())) {
                ordered.add(def.code());
            }
        }
        return ordered;
    }

    public boolean has(String roleCode, String permCode) {
        return permCode != null && effective(roleCode).contains(permCode.trim());
    }

    /**
     * 接口权限校验：任一权限点命中即放行；未登录/无权限抛 {@link SecurityException}（全局 403）。
     */
    public void require(HttpServletRequest request, String... permCodes) {
        LoginUser loginUser = UserUtils.getLoginUser();
        if (loginUser == null) {
            log.warn("[权限] 未登录访问受限接口 uri={}", request.getRequestURI());
            throw new SecurityException("未登录或登录已过期");
        }
        String user = loginUser.getUsername();
        String role = loginUser.getRoleCode();
        if (permCodes == null || permCodes.length == 0) {
            return;
        }
        Set<String> effective = effective(role);
        for (String code : permCodes) {
            if (code != null && effective.contains(code)) {
                return;
            }
        }
        log.warn("[权限] 拒绝访问 user={} 角色={} 所需权限点={} uri={}",
                user, role == null ? "无" : role, String.join("/", permCodes), request.getRequestURI());
        throw new SecurityException("权限不足");
    }

    /**
     * 角色已配置的权限点（原始库值；仅供角色配置页回显）
     */
    public List<String> configured(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Collections.emptyList();
        }
        List<RolePerm> rows = permMapper.selectList(new LambdaQueryWrapper<RolePerm>()
                .eq(RolePerm::getRoleCode, roleCode.trim().toUpperCase())
                .orderByAsc(RolePerm::getId));
        List<String> codes = new ArrayList<>();
        for (RolePerm row : rows) {
            codes.add(row.getPermCode());
        }
        return codes;
    }

    /**
     * 保存角色权限（整表替换）：未知权限点/超出法定边界一律拒绝；变更落审计。
     *
     * @param auditTenant 审计归属租户（角色配置属全局管理动作，通常 default；租户管理员操作记本租户）
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(String roleCode, Collection<String> permCodes, String operator, String ip, String auditTenant) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalArgumentException("角色编码不能为空");
        }
        String rc = roleCode.trim().toUpperCase();
        Set<String> max = PermissionCatalog.statutoryMax(rc);
        if (max.isEmpty()) {
            throw new IllegalArgumentException("不支持的角色：" + roleCode);
        }
        Set<String> target = new LinkedHashSet<>();
        if (permCodes != null) {
            for (String raw : permCodes) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                target.add(raw.trim());
            }
        }
        // 1) 未知权限点：代码目录里没有的一律拒绝（防止前端传脏值被静默落库）
        for (String code : target) {
            if (PermissionCatalog.get(code) == null) {
                throw new IllegalArgumentException("未知权限点：" + code);
            }
        }
        // 2) 法定边界：超出角色职责的权限点拒绝，并把越界项一次说清
        List<String> outOfBound = new ArrayList<>();
        for (String code : target) {
            if (!max.contains(code)) {
                PermissionCatalog.PermDef def = PermissionCatalog.get(code);
                outOfBound.add(def == null ? code : def.name() + "（" + code + "）");
            }
        }
        if (!outOfBound.isEmpty()) {
            throw new SecurityException("权限不足：以下权限点超出角色 " + rc + " 的法定职责边界，不能授予："
                    + String.join("、", outOfBound));
        }
        // 3) 自锁保护：系统管理员至少保留角色配置入口，否则下次无人能改回来
        if (RoleCodeEnum.ADMIN.name().equals(rc)) {
            for (String must : new String[]{PermissionCatalog.MENU_ROLES,
                    PermissionCatalog.ROLE_PERM, PermissionCatalog.ROLE_UPDATE}) {
                if (!target.contains(must)) {
                    throw new IllegalArgumentException("系统管理员必须保留角色配置权限（menu:roles / role:perm / role:update），避免自锁");
                }
            }
        }

        List<String> before = configured(rc);
        permMapper.delete(new LambdaQueryWrapper<RolePerm>().eq(RolePerm::getRoleCode, rc));
        for (String code : target) {
            RolePerm row = new RolePerm();
            row.setRoleCode(rc);
            row.setPermCode(code);
            row.setCreatedAt(LocalDateTime.now());
            permMapper.insert(row);
        }
        cache.remove(rc);
        auditService.record(operator, "ROLE_PERM_UPDATE", rc,
                diffDetail(rc, before, target), ip, auditTenant);
        log.info("[角色] {} 权限已更新：{} 项（操作人 {}）", rc, target.size(), operator);
    }

    /**
     * 权限变更审计文案：新增/移除明细（按目录顺序可读名）
     */
    private String diffDetail(String roleCode, List<String> before, Set<String> after) {
        Set<String> beforeSet = new LinkedHashSet<>(before);
        StringJoiner added = new StringJoiner("、");
        StringJoiner removed = new StringJoiner("、");
        for (PermissionCatalog.PermDef def : PermissionCatalog.all()) {
            boolean in = after.contains(def.code());
            boolean was = beforeSet.contains(def.code());
            if (in && !was) {
                added.add(def.name());
            } else if (!in && was) {
                removed.add(def.name());
            }
        }
        StringBuilder sb = new StringBuilder("角色 ").append(roleCode).append(" 权限变更：共 ")
                .append(after.size()).append(" 项");
        if (added.length() > 0) {
            sb.append("；新增：").append(added);
        }
        if (removed.length() > 0) {
            sb.append("；移除：").append(removed);
        }
        return sb.toString();
    }

    /**
     * 角色权限/状态变更后清缓存（配置保存、角色启停时调用）
     */
    public void invalidate(String roleCode) {
        if (roleCode != null && !roleCode.isBlank()) {
            cache.remove(roleCode.trim().toUpperCase());
        }
    }
}
