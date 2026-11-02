package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.entity.ApiClient;
import com.csssl.entity.CaCompany;
import com.csssl.enums.CompanyStatusEnum;
import com.csssl.mapper.ApiClientMapper;
import com.csssl.mapper.CompanyMapper;
import com.csssl.util.CertUtil;
import com.csssl.vo.CaStatusVO;
import com.csssl.vo.CompanyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司域管理（路线A：每家医院=一套独立根CA，公司间互不信任）。
 * 开公司即建根：登记记录后立即复用 {@link CaService} 带 companyUscc 的初始化路径生成
 * 独立根CA+SUB_CA+Shamir(3,5) 门限份额；suspend/close 本波仅落状态字段，
 * CLOSED 拒绝签发由后续波次在签发/CRL/OCSP 链路 enforce。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyService {

    /**
     * 统一社会信用代码（GB 32100）字符集与加权因子：末位校验码 = 加权和 mod 31 映射
     */
    private static final String USCC_CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    private final CompanyMapper companyMapper;
    private final ApiClientMapper apiClientMapper;
    private final CaService caService;
    private final CssslProperties props;

    /**
     * 公司列表（含各公司根CA根状态摘要，管理页一屏看全根池拓扑）
     */
    public List<CompanyVO> overview() {
        List<CompanyVO> rows = new ArrayList<>();
        for (CaCompany company : list()) {
            rows.add(buildRow(company));
        }
        return rows;
    }

    /**
     * 公司分页查询（管理页：关键字命中 标识/名称/主体O，状态精确匹配；根状态仅对当前页计算）
     */
    public Page<CompanyVO> page(String keyword, String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<CaCompany> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CaCompany::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(CaCompany::getUscc, kw).or().like(CaCompany::getCompanyName, kw)
                    .or().like(CaCompany::getSubjectO, kw));
        }
        qw.orderByAsc(CaCompany::getId);
        Page<CaCompany> page = companyMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return (Page<CompanyVO>) page.convert(this::buildRow);
    }

    /**
     * 单公司状态摘要（详情/创建回执用）
     */
    public CompanyVO overviewOf(String companyUscc) {
        return buildRow(get(companyUscc));
    }

    private CompanyVO buildRow(CaCompany company) {
        CompanyVO vo = CompanyVO.from(company);
        try {
            CaStatusVO st = caService.status(company.getUscc());
            vo.setHasRoot(st.isInitialized());
            vo.setRootSerialHex(st.getSerialHex());
            vo.setRootCn(st.getCn());
            vo.setRootStatus(st.getStatus());
            vo.setRootValidFrom(st.getValidFrom());
            vo.setRootValidTo(st.getValidTo());
            vo.setRootKeyState(st.getRootKeyState());
            if (st.getSubCa() != null) {
                vo.setSubCaSerialHex(st.getSubCa().getSerialHex());
                vo.setSubCaStatus(st.getSubCa().getStatus());
                vo.setSubCaValidFrom(st.getSubCa().getValidFrom());
                vo.setSubCaValidTo(st.getSubCa().getValidTo());
            }
        } catch (Exception e) {
            // 单公司状态异常不拖垮整个列表（如密钥文件损坏）
            vo.setHasRoot(false);
            vo.setStatusError(e.getMessage());
        }
        ApiClient client = apiClientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getCompanyUscc, company.getUscc())
                .orderByAsc(ApiClient::getId)
                .last("LIMIT 1"));
        if (client != null) {
            vo.setApiClientId(client.getId());
            vo.setApiAppId(client.getAppId());
            vo.setApiClientStatus(client.getStatus());
        }
        return vo;
    }

    /**
     * 公司原始记录列表
     */
    public List<CaCompany> list() {
        return companyMapper.selectList(new LambdaQueryWrapper<CaCompany>().orderByAsc(CaCompany::getId));
    }

    /**
     * 按 companyUscc 查询（不存在返回 null）
     */
    public CaCompany findByUscc(String companyUscc) {
        KeyVaultConfig.CompanyVaultResolver.checkCompanyUscc(companyUscc);
        return companyMapper.selectOne(new LambdaQueryWrapper<CaCompany>().eq(CaCompany::getUscc, companyUscc));
    }

    /**
     * 按 companyUscc 查询（不存在抛异常）
     */
    public CaCompany get(String companyUscc) {
        CaCompany company = findByUscc(companyUscc);
        if (company == null) {
            throw new IllegalArgumentException("公司不存在: " + companyUscc);
        }
        return company;
    }

    /**
     * 校验公司存在且 ACTIVE（根CA管理写操作前置；default 域存量语义不受公司表约束）
     */
    public void requireActive(String companyUscc) {
        CaCompany company = get(companyUscc);
        if (!CompanyStatusEnum.ACTIVE.is(company.getStatus())) {
            throw new IllegalArgumentException("公司状态不允许该操作: " + companyUscc + "（" + statusText(company.getStatus()) + "）");
        }
    }

    /**
     * 统一社会信用代码规整：去空格、转大写（唯一性按规整后的值判定）
     */
    public static String normalizeUscc(String uscc) {
        return uscc == null ? "" : uscc.trim().toUpperCase();
    }

    /**
     * 新建公司参数预检（变更单提交时先验一次快速失败；执行时 create() 再验一次，以执行时为准）
     */
    public void validateCreate(String uscc, String companyName, String subjectO, String algorithm, String contactPhone) {
        String code = normalizeUscc(uscc);
        if (!isValidUscc(code)) {
            throw new IllegalArgumentException("统一社会信用代码校验不通过，请核对18位代码与末位校验码");
        }
        if (companyName == null || companyName.isBlank() || companyName.trim().length() > 64) {
            throw new IllegalArgumentException("公司名称必填且不超过64字符");
        }
        if (subjectO == null || subjectO.isBlank() || subjectO.trim().length() > 128) {
            throw new IllegalArgumentException("证书主体O必填且不超过128字符");
        }
        String phone = contactPhone == null || contactPhone.isBlank() ? null : contactPhone.trim();
        if (phone != null && !phone.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("联系方式须为11位手机号");
        }
        CertUtil.checkAlgorithm(resolveAlgorithm(algorithm));
        CaCompany sameCode = companyMapper.selectOne(new LambdaQueryWrapper<CaCompany>()
                .eq(CaCompany::getUscc, code));
        if (sameCode != null) {
            throw new IllegalArgumentException("该统一社会信用代码已登记公司：" + sameCode.getCompanyName()
                    + "（" + statusText(sameCode.getStatus()) + "）"
                    + (CompanyStatusEnum.CLOSED.is(sameCode.getStatus())
                            ? "，已关闭公司的根CA密钥与历史证书仍在，不能同码另建，如需重新服务请对该公司发起「申请恢复」" : ""));
        }
        if (companyMapper.selectCount(new LambdaQueryWrapper<CaCompany>()
                .eq(CaCompany::getSubjectO, subjectO.trim())) > 0) {
            throw new IllegalArgumentException("证书主体O已被其他公司占用: " + subjectO);
        }
    }

    /**
     * 算法缺省取全局配置 csssl.root-ca.algorithm（新公司"开公司默认参数"约定）
     */
    public String resolveAlgorithm(String algorithm) {
        return algorithm == null || algorithm.isBlank()
                ? props.getRootCa().getAlgorithm() : algorithm.trim();
    }

    /**
     * 新建公司并立即初始化独立根CA+SUB_CA+门限份额。
     * 统一社会信用代码即公司标识，唯一性由该代码保证（不另设内部标识）。
     */
    public CaCompany create(String uscc, String companyName, String subjectO, String algorithm,
                        String contact, String contactPhone, String description) {
        String code = normalizeUscc(uscc);
        String alg = resolveAlgorithm(algorithm);
        validateCreate(code, companyName, subjectO, alg, contactPhone);
        String phone = contactPhone == null || contactPhone.isBlank() ? null : contactPhone.trim();

        CaCompany company = new CaCompany();
        company.setUscc(code);
        company.setCompanyName(companyName.trim());
        company.setSubjectO(subjectO.trim());
        company.setAlgorithm(alg);
        company.setStatus(CompanyStatusEnum.ACTIVE.getCode());
        company.setContact(contact);
        company.setContactPhone(phone);
        company.setDescription(description);
        company.setCreatedAt(LocalDateTime.now());
        companyMapper.insert(company);
        log.info("[公司] 已登记公司 {}（{}，主体O={}，算法={}）", code, company.getCompanyName(),
                company.getSubjectO(), alg);

        // 开公司即建根：独立根CA → SUB_CA → 门限份额（复用带公司标识的初始化路径，幂等）
        caService.initRoot(code);

        // 平台唯一在营公司即主公司：无公司参数入口（审计/兼容调用）归位到它，无需重启
        Long actives = companyMapper.selectCount(new LambdaQueryWrapper<CaCompany>()
                .eq(CaCompany::getStatus, CompanyStatusEnum.ACTIVE.getCode()));
        if (actives != null && actives == 1) {
            CaCompany.PRIMARY_COMPANY_USCC = code;
        }
        return company;
    }

    /**
     * 暂停公司（ACTIVE → SUSPENDED）
     */
    public CaCompany suspend(String companyUscc) {
        return updateStatus(companyUscc, CompanyStatusEnum.SUSPENDED, List.of(CompanyStatusEnum.ACTIVE));
    }

    /**
     * 恢复公司（SUSPENDED/CLOSED → ACTIVE）
     *
     * <p>关闭只翻状态字段，根CA密钥、已签发证书与接入凭证都在原地未动，故关闭后同码既不能再新建
     * （{@code uk_uscc} 占位）也不需要新建——要重新服务这家机构就走本方法把它恢复回启用。</p>
     */
    public CaCompany resume(String companyUscc) {
        return updateStatus(companyUscc, CompanyStatusEnum.ACTIVE,
                List.of(CompanyStatusEnum.SUSPENDED, CompanyStatusEnum.CLOSED));
    }

    /**
     * 关闭公司（ACTIVE → CLOSED：停签发、停 CRL 发布、接入凭证鉴权拒绝，密钥保留待恢复或运维处置）
     */
    public CaCompany close(String companyUscc) {
        return updateStatus(companyUscc, CompanyStatusEnum.CLOSED, List.of(CompanyStatusEnum.ACTIVE, CompanyStatusEnum.SUSPENDED));
    }

    private CaCompany updateStatus(String companyUscc, CompanyStatusEnum target, List<CompanyStatusEnum> fromAllowed) {
        CaCompany company = get(companyUscc);
        boolean allowed = fromAllowed.stream().anyMatch(s -> s.is(company.getStatus()));
        if (!allowed) {
            throw new IllegalArgumentException("公司当前状态（" + statusText(company.getStatus())
                    + "）不允许变更为 " + target.getLabel());
        }
        company.setStatus(target.getCode());
        companyMapper.updateById(company);
        log.info("[公司] {} 状态变更为 {}", companyUscc, target.getCode());
        return company;
    }

    private String statusText(String status) {
        CompanyStatusEnum s = CompanyStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    /**
     * 统一社会信用代码校验（GB 32100）：18 位、字符集合法、末位校验码 = (31 - 加权和 mod 31) mod 31
     */
    static boolean isValidUscc(String code) {
        if (code == null || code.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int idx = USCC_CHARSET.indexOf(code.charAt(i));
            if (idx < 0) {
                return false;
            }
            sum += idx * USCC_WEIGHTS[i];
        }
        return USCC_CHARSET.charAt((31 - sum % 31) % 31) == code.charAt(17);
    }
}
