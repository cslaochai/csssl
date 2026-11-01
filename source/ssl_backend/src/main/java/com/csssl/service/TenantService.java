package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.entity.ApiClient;
import com.csssl.entity.CaTenant;
import com.csssl.enums.TenantStatusEnum;
import com.csssl.mapper.ApiClientMapper;
import com.csssl.mapper.CaTenantMapper;
import com.csssl.util.CertUtil;
import com.csssl.vo.CaStatusVO;
import com.csssl.vo.TenantVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 租户域管理（路线A：每家医院=一套独立根CA，租户间互不信任）。
 * 开租户即建根：登记记录后立即复用 {@link CaService} 带 tenantUscc 的初始化路径生成
 * 独立根CA+SUB_CA+Shamir(3,5) 门限份额；suspend/close 本波仅落状态字段，
 * CLOSED 拒绝签发由后续波次在签发/CRL/OCSP 链路 enforce。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    /**
     * 统一社会信用代码（GB 32100）字符集与加权因子：末位校验码 = 加权和 mod 31 映射
     */
    private static final String USCC_CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    private final CaTenantMapper tenantMapper;
    private final ApiClientMapper apiClientMapper;
    private final CaService caService;
    private final CssslProperties props;

    /**
     * 租户列表（含各租户根CA根状态摘要，管理页一屏看全根池拓扑）
     */
    public List<TenantVO> overview() {
        List<TenantVO> rows = new ArrayList<>();
        for (CaTenant tenant : list()) {
            rows.add(buildRow(tenant));
        }
        return rows;
    }

    /**
     * 租户分页查询（管理页：关键字命中 标识/名称/主体O，状态精确匹配；根状态仅对当前页计算）
     */
    public Page<TenantVO> page(String keyword, String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<CaTenant> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CaTenant::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(CaTenant::getUscc, kw).or().like(CaTenant::getTenantName, kw)
                    .or().like(CaTenant::getSubjectO, kw));
        }
        qw.orderByAsc(CaTenant::getId);
        Page<CaTenant> page = tenantMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return (Page<TenantVO>) page.convert(this::buildRow);
    }

    /**
     * 单租户状态摘要（详情/创建回执用）
     */
    public TenantVO overviewOf(String tenantUscc) {
        return buildRow(get(tenantUscc));
    }

    private TenantVO buildRow(CaTenant tenant) {
        TenantVO vo = TenantVO.from(tenant);
        try {
            CaStatusVO st = caService.status(tenant.getUscc());
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
            // 单租户状态异常不拖垮整个列表（如密钥文件损坏）
            vo.setHasRoot(false);
            vo.setStatusError(e.getMessage());
        }
        ApiClient client = apiClientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getTenantUscc, tenant.getUscc())
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
     * 租户原始记录列表
     */
    public List<CaTenant> list() {
        return tenantMapper.selectList(new LambdaQueryWrapper<CaTenant>().orderByAsc(CaTenant::getId));
    }

    /**
     * 按 tenantUscc 查询（不存在返回 null）
     */
    public CaTenant findByUscc(String tenantUscc) {
        KeyVaultConfig.TenantVaultResolver.checkTenantUscc(tenantUscc);
        return tenantMapper.selectOne(new LambdaQueryWrapper<CaTenant>().eq(CaTenant::getUscc, tenantUscc));
    }

    /**
     * 按 tenantUscc 查询（不存在抛异常）
     */
    public CaTenant get(String tenantUscc) {
        CaTenant tenant = findByUscc(tenantUscc);
        if (tenant == null) {
            throw new IllegalArgumentException("租户不存在: " + tenantUscc);
        }
        return tenant;
    }

    /**
     * 校验租户存在且 ACTIVE（根CA管理写操作前置；default 域存量语义不受租户表约束）
     */
    public void requireActive(String tenantUscc) {
        CaTenant tenant = get(tenantUscc);
        if (!TenantStatusEnum.ACTIVE.is(tenant.getStatus())) {
            throw new IllegalArgumentException("租户状态不允许该操作: " + tenantUscc + "（" + statusText(tenant.getStatus()) + "）");
        }
    }

    /**
     * 统一社会信用代码规整：去空格、转大写（唯一性按规整后的值判定）
     */
    public static String normalizeUscc(String uscc) {
        return uscc == null ? "" : uscc.trim().toUpperCase();
    }

    /**
     * 新建租户参数预检（变更单提交时先验一次快速失败；执行时 create() 再验一次，以执行时为准）
     */
    public void validateCreate(String uscc, String tenantName, String subjectO, String algorithm, String contactPhone) {
        String code = normalizeUscc(uscc);
        if (!isValidUscc(code)) {
            throw new IllegalArgumentException("统一社会信用代码校验不通过，请核对18位代码与末位校验码");
        }
        if (tenantName == null || tenantName.isBlank() || tenantName.trim().length() > 64) {
            throw new IllegalArgumentException("租户名称必填且不超过64字符");
        }
        if (subjectO == null || subjectO.isBlank() || subjectO.trim().length() > 128) {
            throw new IllegalArgumentException("证书主体O必填且不超过128字符");
        }
        String phone = contactPhone == null || contactPhone.isBlank() ? null : contactPhone.trim();
        if (phone != null && !phone.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("联系方式须为11位手机号");
        }
        CertUtil.checkAlgorithm(resolveAlgorithm(algorithm));
        CaTenant sameCode = tenantMapper.selectOne(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getUscc, code));
        if (sameCode != null) {
            throw new IllegalArgumentException("该统一社会信用代码已登记租户：" + sameCode.getTenantName()
                    + "（" + statusText(sameCode.getStatus()) + "）"
                    + (TenantStatusEnum.CLOSED.is(sameCode.getStatus())
                            ? "，已关闭租户的根CA密钥与历史证书仍在，不能同码另建，如需重新服务请对该租户发起「申请恢复」" : ""));
        }
        if (tenantMapper.selectCount(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getSubjectO, subjectO.trim())) > 0) {
            throw new IllegalArgumentException("证书主体O已被其他租户占用: " + subjectO);
        }
    }

    /**
     * 算法缺省取全局配置 csssl.root-ca.algorithm（新租户"开租户默认参数"约定）
     */
    public String resolveAlgorithm(String algorithm) {
        return algorithm == null || algorithm.isBlank()
                ? props.getRootCa().getAlgorithm() : algorithm.trim();
    }

    /**
     * 新建租户并立即初始化独立根CA+SUB_CA+门限份额。
     * 统一社会信用代码即租户标识，唯一性由该代码保证（不另设内部标识）。
     */
    public CaTenant create(String uscc, String tenantName, String subjectO, String algorithm,
                        String contact, String contactPhone, String description) {
        String code = normalizeUscc(uscc);
        String alg = resolveAlgorithm(algorithm);
        validateCreate(code, tenantName, subjectO, alg, contactPhone);
        String phone = contactPhone == null || contactPhone.isBlank() ? null : contactPhone.trim();

        CaTenant tenant = new CaTenant();
        tenant.setUscc(code);
        tenant.setTenantName(tenantName.trim());
        tenant.setSubjectO(subjectO.trim());
        tenant.setAlgorithm(alg);
        tenant.setStatus(TenantStatusEnum.ACTIVE.getCode());
        tenant.setContact(contact);
        tenant.setContactPhone(phone);
        tenant.setDescription(description);
        tenant.setCreatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        log.info("[租户] 已登记租户 {}（{}，主体O={}，算法={}）", code, tenant.getTenantName(),
                tenant.getSubjectO(), alg);

        // 开租户即建根：独立根CA → SUB_CA → 门限份额（复用带租户标识的初始化路径，幂等）
        caService.initRoot(code);

        // 平台唯一在营租户即主租户：无租户参数入口（审计/兼容调用）归位到它，无需重启
        Long actives = tenantMapper.selectCount(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getStatus, TenantStatusEnum.ACTIVE.getCode()));
        if (actives != null && actives == 1) {
            CaTenant.PRIMARY_TENANT_USCC = code;
        }
        return tenant;
    }

    /**
     * 暂停租户（ACTIVE → SUSPENDED）
     */
    public CaTenant suspend(String tenantUscc) {
        return updateStatus(tenantUscc, TenantStatusEnum.SUSPENDED, List.of(TenantStatusEnum.ACTIVE));
    }

    /**
     * 恢复租户（SUSPENDED/CLOSED → ACTIVE）
     *
     * <p>关闭只翻状态字段，根CA密钥、已签发证书与接入凭证都在原地未动，故关闭后同码既不能再新建
     * （{@code uk_uscc} 占位）也不需要新建——要重新服务这家机构就走本方法把它恢复回启用。</p>
     */
    public CaTenant resume(String tenantUscc) {
        return updateStatus(tenantUscc, TenantStatusEnum.ACTIVE,
                List.of(TenantStatusEnum.SUSPENDED, TenantStatusEnum.CLOSED));
    }

    /**
     * 关闭租户（ACTIVE → CLOSED：停签发、停 CRL 发布、接入凭证鉴权拒绝，密钥保留待恢复或运维处置）
     */
    public CaTenant close(String tenantUscc) {
        return updateStatus(tenantUscc, TenantStatusEnum.CLOSED, List.of(TenantStatusEnum.ACTIVE, TenantStatusEnum.SUSPENDED));
    }

    private CaTenant updateStatus(String tenantUscc, TenantStatusEnum target, List<TenantStatusEnum> fromAllowed) {
        CaTenant tenant = get(tenantUscc);
        boolean allowed = fromAllowed.stream().anyMatch(s -> s.is(tenant.getStatus()));
        if (!allowed) {
            throw new IllegalArgumentException("租户当前状态（" + statusText(tenant.getStatus())
                    + "）不允许变更为 " + target.getLabel());
        }
        tenant.setStatus(target.getCode());
        tenantMapper.updateById(tenant);
        log.info("[租户] {} 状态变更为 {}", tenantUscc, target.getCode());
        return tenant;
    }

    private String statusText(String status) {
        TenantStatusEnum s = TenantStatusEnum.fromCode(status);
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
