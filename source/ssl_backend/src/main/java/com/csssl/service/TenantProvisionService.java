package com.csssl.service;

import com.csssl.entity.CaTenant;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.TenantCreateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新建租户（直接执行）：登记租户 → 初始化独立根CA+SUB_CA+门限份额 → 登记默认接入凭证。
 *
 * <p>新建属常规建档动作，不设双人复核；暂停/恢复/关闭/重置接入密钥等生命周期变更仍走
 * {@link TenantOpTicketService}。本类只做编排，参数校验与状态机在 {@link TenantService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisionService {

    private final TenantService tenantService;
    private final ApiClientService apiClientService;
    private final AuditService auditService;

    /**
     * 新建租户并发放默认接入凭证；secret 只在本次响应返回一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantCreateVO create(String uscc, String tenantName, String subjectO, String algorithm,
                                 String contact, String contactPhone, String description,
                                 String username, String ip) {
        CaTenant tenant = tenantService.create(uscc, tenantName, subjectO, algorithm,
                contact, contactPhone, description);
        ApiClientSecretVO credential = apiClientService.create(tenant.getUscc(),
                ApiClientService.DEFAULT_CLIENT_NAME, null, null, username, ip);
        auditService.record(username, "TENANT_CREATE", tenant.getUscc(),
                "新建租户 " + tenant.getTenantName() + "（统一社会信用代码=" + tenant.getUscc()
                        + "，主体O=" + tenant.getSubjectO() + "，算法=" + tenant.getAlgorithm()
                        + "），已初始化独立根CA与SUB_CA并登记默认接入凭证（appId="
                        + credential.getAppId() + "）",
                ip, tenant.getUscc());

        TenantCreateVO vo = new TenantCreateVO();
        vo.setTenantUscc(tenant.getUscc());
        vo.setTenantName(tenant.getTenantName());
        vo.setSubjectO(tenant.getSubjectO());
        vo.setAlgorithm(tenant.getAlgorithm());
        vo.setAppId(credential.getAppId());
        vo.setSecret(credential.getSecret());
        vo.setSecretVersion(credential.getSecretVersion());
        log.info("[租户] 直接新建完成 uscc={} tenant={} appId={}", tenant.getUscc(),
                tenant.getTenantName(), credential.getAppId());
        return vo;
    }
}
