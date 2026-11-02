package com.csssl.service;

import com.csssl.entity.CaCompany;
import com.csssl.service.CaService;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.CompanyCreateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 新建公司（直接执行）：登记公司 → 初始化独立根CA+SUB_CA+门限份额 → 指定 5 名密钥保管人（一人一份落表）
 * → 登记默认接入凭证。
 *
 * <p>新建属常规建档动作，不设双人复核；暂停/恢复/关闭/重置接入密钥等生命周期变更仍走
 * {@link CompanyOpTicketService}。本类只做编排，参数校验与状态机在 {@link CompanyService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProvisionService {

    private final CompanyService companyService;
    private final ApiClientService apiClientService;
    private final AuditService auditService;
    private final CaService caService;

    /**
     * 新建公司并发放默认接入凭证；secret 只在本次响应返回一次。
     * 开公司即建根，并指定 5 名密钥份额保管人（建根仪式上指定 officers，一人一份，后续动用根私钥需 3-of-5 审批）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyCreateVO create(String uscc, String companyName, String subjectO, String algorithm,
                                 String contact, String contactPhone, String description,
                                 List<String> shareHolders, String username, String ip) {
        CaCompany company = companyService.create(uscc, companyName, subjectO, algorithm,
                contact, contactPhone, description);
        ApiClientSecretVO credential = apiClientService.create(company.getUscc(),
                ApiClientService.DEFAULT_CLIENT_NAME, null, null, username, ip);
        // 建根（initRoot 已在 companyService.create 内完成）→ 指定 5 名密钥份额保管人，一人一份落表
        caService.assignRootShares(company.getUscc(), shareHolders);
        auditService.record(username, "COMPANY_CREATE", company.getUscc(),
                "新建公司 " + company.getCompanyName() + "（统一社会信用代码=" + company.getUscc()
                        + "，主体O=" + company.getSubjectO() + "，算法=" + company.getAlgorithm()
                        + "），已初始化独立根CA与SUB_CA、指定 " + (shareHolders == null ? 0 : shareHolders.size())
                        + " 名密钥保管人并登记默认接入凭证（appId=" + credential.getAppId() + "）",
                ip, company.getUscc());

        CompanyCreateVO vo = new CompanyCreateVO();
        vo.setCompanyUscc(company.getUscc());
        vo.setCompanyName(company.getCompanyName());
        vo.setSubjectO(company.getSubjectO());
        vo.setAlgorithm(company.getAlgorithm());
        vo.setAppId(credential.getAppId());
        vo.setSecret(credential.getSecret());
        vo.setSecretVersion(credential.getSecretVersion());
        vo.setShareHolders(shareHolders);
        log.info("[公司] 直接新建完成 uscc={} company={} appId={} holders={}", company.getUscc(),
                company.getCompanyName(), credential.getAppId(), shareHolders);
        return vo;
    }
}
