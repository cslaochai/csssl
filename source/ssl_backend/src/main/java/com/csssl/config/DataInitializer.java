package com.csssl.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.RolePerm;
import com.csssl.entity.SysRole;
import com.csssl.entity.SysUser;
import com.csssl.enums.RoleCodeEnum;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.mapper.RolePermMapper;
import com.csssl.mapper.SysRoleMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.CertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 启动初始化：确保默认管理员账号存在（首次启动创建 admin / 123456，密码 BCrypt 存储）、
 * 初始化内置证书模板（GM/T 0034 §5.3.4）、执行过期证书归档扫描（§12 生命周期）。
 * 租户不在此处创建：空库由管理员在租户管理页开首个租户（开租户即初始化该租户独立根CA）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final CertTemplateMapper templateMapper;
    private final SysRoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final CertService certService;

    @Override
    public void run(ApplicationArguments args) {
        initRoles();
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (count == null || count == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPassword(BCrypt.hashpw("123456", BCrypt.gensalt()));
            admin.setRealName("系统管理员");
            admin.setRoleCode("ADMIN");
            admin.setTenantKey(null); // 默认管理员=全局账号（tenant_key NULL：跨租户总览，租户域操作仍须显式传 tenantKey）
            admin.setStatus(1);
            admin.setCreatedAt(LocalDateTime.now());
            userMapper.insert(admin);
            log.warn("[初始化] 已创建默认管理员账号 admin / 123456，请登录后尽快修改口令");
        }
        initTemplates();
        try {
            int n = certService.expireScan();
            if (n > 0) {
                log.info("[初始化] 过期证书归档：{} 张已标记 EXPIRED", n);
            }
        } catch (Exception e) {
            log.warn("[初始化] 过期归档扫描异常（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 预置角色初始化（幂等，唯一来源为 {@link RoleCodeEnum} 枚举）：
     * 角色不存在则建档案并按其法定上限（defaultMax）播种默认权限；角色已存在则完全不动——
     * 管理员在角色配置页收窄过的授权不会被重启"复原"（幂等只补缺，不覆盖）。
     * 自定义角色（builtin=0）不在此处创建，由管理员在角色配置页后台自建。
     */
    private void initRoles() {
        for (RoleCodeEnum rc : RoleCodeEnum.values()) {
            String code = rc.getCode();
            Set<String> max = rc.defaultMax();
            Long exist = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, code));
            if (exist != null && exist > 0) {
                // 存量角色：清理超出法定上限的脏授权（历史上被误授/权限目录调整后的残留），
                // 只删越界项，不动管理员在界内的收窄结果
                if (!max.isEmpty()) {
                    int pruned = rolePermMapper.delete(new LambdaQueryWrapper<RolePerm>()
                            .eq(RolePerm::getRoleCode, code)
                            .notIn(RolePerm::getPermCode, max));
                    if (pruned > 0) {
                        log.warn("[初始化] 角色 {} 清理越界授权 {} 项（超出 GM/T 0034 法定边界）", code, pruned);
                    }
                }
                continue;
            }
            SysRole role = new SysRole();
            role.setRoleCode(code);
            role.setRoleName(rc.getRoleName());
            role.setRoleDesc(rc.getRoleDesc());
            role.setSortOrder(rc.getSortOrder());
            role.setStatus(1);
            role.setBuiltin(rc.isBuiltin() ? 1 : 0);
            role.setCreatedAt(LocalDateTime.now());
            role.setUpdatedAt(LocalDateTime.now());
            roleMapper.insert(role);

            // 默认授权=法定上限，保证升级后行为与改造前一致（预置角色原有能力不减）
            for (String permCode : max) {
                RolePerm row = new RolePerm();
                row.setRoleCode(code);
                row.setPermCode(permCode);
                row.setCreatedAt(LocalDateTime.now());
                rolePermMapper.insert(row);
            }
            log.info("[初始化] 已创建预置角色：{}（{}），默认授权 {} 项", code, rc.getRoleName(), max.size());
        }
    }

    /**
     * 内置证书模板（编码唯一，重复启动幂等；KeyUsage/EKU/策略OID 锁定，签发按模板）。
     * 内置模板（SERVER_COMM/DOCTOR_SIGN/SUB_CA）保持全局：tenant_key=NULL，所有租户可见。
     */
    private void initTemplates() {
        insertIfAbsent("SERVER_COMM", "租户通信证书", "SERVER", "SM2", 730,
                "digitalSignature,keyEncipherment", "serverAuth",
                "1.2.156.112562.2.2.15", "医保前置/局端等系统间加密传输通信证书（SM2 签名证书策略）");
        insertIfAbsent("DOCTOR_SIGN", "医生签名证书", "CLIENT", "SM2", 365,
                "digitalSignature", "clientAuth",
                "1.2.156.112562.2.2.15", "医护电子签名证书（病历/处方签名，私钥申请方自持）");
        insertIfAbsent("SUB_CA", "二级CA签发证书", "SUB_CA", "RSA2048", 730,
                "keyCertSign,cRLSign", null,
                null, "二级CA证书（与根CA体系一致，支持轮换）");
    }

    private void insertIfAbsent(String code, String name, String certType, String algorithm, int days,
                                String keyUsage, String eku, String policyOid, String desc) {
        if (templateMapper.selectCount(new LambdaQueryWrapper<CertTemplate>()
                .eq(CertTemplate::getTemplateCode, code)) > 0) {
            return;
        }
        CertTemplate tpl = new CertTemplate();
        tpl.setTenantKey(null); // 内置模板=全局模板（ssl_cert_template.tenant_key NULL：所有租户可见集内）
        tpl.setTemplateCode(code);
        tpl.setTemplateName(name);
        tpl.setCertType(certType);
        tpl.setAlgorithm(algorithm);
        tpl.setValidityDays(days);
        tpl.setKeyUsage(keyUsage);
        tpl.setExtKeyUsage(eku);
        tpl.setPolicyOid(policyOid);
        tpl.setBuiltin(1);
        tpl.setDescription(desc);
        tpl.setCreatedAt(LocalDateTime.now());
        templateMapper.insert(tpl);
        log.info("[初始化] 已创建内置证书模板：{}（{}）", code, name);
    }
}
