package com.csssl.service;

import com.csssl.entity.CaCompany;
import com.csssl.entity.CaSystem;
import com.csssl.entity.LoginUser;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.CompanyCreateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 新建公司（直接执行）：登记公司 → 初始化独立根CA+SUB_CA+门限份额 → 用「系统名称」创建首个系统
 * → 为该首个系统登记接入凭证 → 指定 5 名密钥保管人。
 *
 * <p>模型：公司只是客户主体（壳），系统才是证书域单元。系统名称即证书主体 O，公司内唯一；
 * 因此建公司时表单里的「系统名称」会同时写进公司档案 subject_o 与 ssl_ca_system。
 * <b>不再创建 DEFAULT 默认客户端</b>——接入凭证一律归属到具体系统。</p>
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
    private final CaSystemService caSystemService;
    private final AuditService auditService;
    private final CaService caService;

    /**
     * 新建公司并发放「首个系统」的接入凭证；secret 只在本次响应返回一次。
     * 开公司即建根，并指定 5 名密钥份额保管人（动用根私钥需 3-of-5 审批）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyCreateVO create(String uscc, String companyName, String subjectO, String algorithm,
                                  String contact, String contactPhone, String description,
                                  List<String> shareHolders, LoginUser actor, String ip) {
        CaCompany company = companyService.create(uscc, companyName, subjectO, algorithm,
                contact, contactPhone, description);
        // 建根（initRoot 已在 companyService.create 内完成）→ 指定 5 名密钥份额保管人，一人一份落表
        caService.assignRootShares(company.getUscc(), shareHolders);

        // 系统名称 = 主体 O：创建该公司首个系统（幂等；后续系统由「系统接入」单独新增）
        CaSystem system = caSystemService.getOrCreate(company.getUscc(), company.getSubjectO(),
                "建公司时创建的首个系统", actor, ip);

        // 为该首个系统登记接入凭证（appId/appSecret 归系统，但 vault 仍按公司域存放，避免改动已验证链路）
        // 凭证名称留空：由服务端落系统名，与「系统接入」页的展示口径一致
        ApiClientSecretVO credential = apiClientService.create(company.getUscc(),
                null, system.getSystemName(),
                null, null, null, actor, ip);

        auditService.record(actor, "COMPANY_CREATE", company.getUscc(),
                "新建公司 " + company.getCompanyName() + "（统一社会信用代码=" + company.getUscc()
                        + "，首个系统=" + system.getSystemName() + "，算法=" + company.getAlgorithm()
                        + "），已初始化独立根CA与SUB_CA、指定 " + (shareHolders == null ? 0 : shareHolders.size())
                        + " 名密钥保管人，并为系统 " + system.getSystemName()
                        + " 登记接入凭证（appId=" + credential.getAppId() + "）",
                ip, company.getUscc());

        CompanyCreateVO vo = new CompanyCreateVO();
        vo.setCompanyUscc(company.getUscc());
        vo.setCompanyName(company.getCompanyName());
        vo.setSubjectO(company.getSubjectO());
        vo.setSystemName(system.getSystemName());
        vo.setAlgorithm(company.getAlgorithm());
        vo.setAppId(credential.getAppId());
        vo.setSecret(credential.getSecret());
        vo.setSecretVersion(credential.getSecretVersion());
        vo.setShareHolders(shareHolders);
        log.info("[公司] 直接新建完成 uscc={} company={} firstSystem={} appId={} holders={}",
                company.getUscc(), company.getCompanyName(), system.getSystemName(),
                credential.getAppId(), shareHolders);
        return vo;
    }
}
