package com.csssl.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaCert;
import com.csssl.entity.CaOrg;
import com.csssl.entity.CertTemplate;
import com.csssl.entity.SysUser;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CaOrgMapper;
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
 * 启动初始化：确保院内默认域机构存在（路线A兼容位）、默认管理员账号存在
 * （首次启动创建 admin / 123456，密码 BCrypt 存储）、初始化内置证书模板（GM/T 0034 §5.3.4）、
 * 执行过期证书归档扫描（§12 生命周期）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final CertTemplateMapper templateMapper;
    private final CaOrgMapper orgMapper;
    private final CaCertMapper certMapper;
    private final CssslProperties props;
    private final CertService certService;

    @Override
    public void run(ApplicationArguments args) {
        initDefaultOrg();
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (count == null || count == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPassword(BCrypt.hashpw("123456", BCrypt.gensalt()));
            admin.setRealName("系统管理员");
            admin.setRoleCode("ADMIN");
            admin.setOrgKey(null); // 默认管理员=全局账号（org_key NULL：跨机构总览，机构域操作仍须显式传 orgKey）
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
     * 院内默认域机构（路线A兼容铁律：存量单根原样归位 default，subject_o='CSSSL'）。
     * 算法优先取存量根CA记录的算法（与磁盘密钥材料一致），无存量根时取配置默认。
     */
    private void initDefaultOrg() {
        if (orgMapper.selectCount(new LambdaQueryWrapper<CaOrg>()
                .eq(CaOrg::getOrgKey, CaOrg.DEFAULT_ORG_KEY)) > 0) {
            return;
        }
        String algorithm = props.getRootCa().getAlgorithm();
        CaCert root = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCertType, "ROOT")
                .orderByDesc(CaCert::getCreatedAt)
                .last("limit 1"));
        if (root != null && root.getAlgorithm() != null && !root.getAlgorithm().isBlank()) {
            algorithm = root.getAlgorithm();
        }
        CaOrg org = new CaOrg();
        org.setOrgKey(CaOrg.DEFAULT_ORG_KEY);
        org.setOrgName("院内默认域");
        org.setSubjectO("CSSSL");
        org.setAlgorithm(algorithm);
        org.setStatus("ACTIVE");
        org.setDescription("存量单根兼容域：路径/DN/主密钥沿用旧根，老对接（cslk/csmig）零破坏");
        org.setCreatedAt(LocalDateTime.now());
        orgMapper.insert(org);
        log.info("[初始化] 已创建院内默认域机构 default（算法={}）", algorithm);
    }

    /** 内置证书模板（编码唯一，重复启动幂等；KeyUsage/EKU/策略OID 锁定，签发按模板）。
     *  内置模板（SERVER_COMM/DOCTOR_SIGN/SUB_CA）保持全局：org_key=NULL，所有机构可见。 */
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
        tpl.setOrgKey(null); // 内置模板=全局模板（ssl_cert_template.org_key NULL：所有机构可见集内）
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
