package com.csssl.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.SysUser;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.CertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动初始化：确保默认管理员账号存在（首次启动创建 admin / 123456，密码 BCrypt 存储）、
 * 初始化内置证书模板（GM/T 0034 §5.3.4）、执行过期证书归档扫描（§12 生命周期）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final CertTemplateMapper templateMapper;
    private final CertService certService;

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (count == null || count == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPassword(BCrypt.hashpw("123456", BCrypt.gensalt()));
            admin.setRealName("系统管理员");
            admin.setRoleCode("ADMIN");
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

    /** 内置证书模板（编码唯一，重复启动幂等；KeyUsage/EKU/策略OID 锁定，签发按模板） */
    private void initTemplates() {
        insertIfAbsent("SERVER_COMM", "机构通信证书", "SERVER", "SM2", 730,
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
