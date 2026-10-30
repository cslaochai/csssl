package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.entity.CaTenant;
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
import java.util.regex.Pattern;

/**
 * 租户域管理（路线A：每家医院=一套独立根CA，租户间互不信任）。
 * 开租户即建根：登记记录后立即复用 {@link CaService} 带 tenantKey 的初始化路径生成
 * 独立根CA+SUB_CA+Shamir(3,5) 门限份额；suspend/close 本波仅落状态字段，
 * CLOSED 拒绝签发由后续波次在签发/CRL/OCSP 链路 enforce。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CLOSED = "CLOSED";
    /**
     * 租户标识格式：小写字母数字（同时兜住路径穿越——tenantKey 直接参与 store-dir/tenants/{tenantKey} 拼路）
     */
    private static final Pattern TENANT_KEY = Pattern.compile("^[a-z0-9]{1,32}$");
    private final CaTenantMapper tenantMapper;
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
            qw.and(w -> w.like(CaTenant::getTenantKey, kw).or().like(CaTenant::getTenantName, kw).or().like(CaTenant::getSubjectO, kw));
        }
        qw.orderByAsc(CaTenant::getId);
        Page<CaTenant> page = tenantMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return (Page<TenantVO>) page.convert(this::buildRow);
    }

    /**
     * 单租户状态摘要（详情/创建回执用）
     */
    public TenantVO overviewOf(String tenantKey) {
        return buildRow(get(tenantKey));
    }

    private TenantVO buildRow(CaTenant tenant) {
        TenantVO vo = TenantVO.from(tenant);
        try {
            CaStatusVO st = caService.status(tenant.getTenantKey());
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
        return vo;
    }

    /**
     * 租户原始记录列表
     */
    public List<CaTenant> list() {
        return tenantMapper.selectList(new LambdaQueryWrapper<CaTenant>().orderByAsc(CaTenant::getId));
    }

    /**
     * 按 tenantKey 查询（不存在返回 null）
     */
    public CaTenant findByKey(String tenantKey) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        return tenantMapper.selectOne(new LambdaQueryWrapper<CaTenant>().eq(CaTenant::getTenantKey, tenantKey));
    }

    /**
     * 按 tenantKey 查询（不存在抛异常）
     */
    public CaTenant get(String tenantKey) {
        CaTenant tenant = findByKey(tenantKey);
        if (tenant == null) {
            throw new IllegalArgumentException("租户不存在: " + tenantKey);
        }
        return tenant;
    }

    /**
     * 校验租户存在且 ACTIVE（根CA管理写操作前置；default 域存量语义不受租户表约束）
     */
    public void requireActive(String tenantKey) {
        CaTenant tenant = get(tenantKey);
        if (!STATUS_ACTIVE.equals(tenant.getStatus())) {
            throw new IllegalArgumentException("租户状态不允许该操作: " + tenantKey + "（" + tenant.getStatus() + "）");
        }
    }

    /**
     * 新建租户并立即初始化独立根CA+SUB_CA+门限份额。
     * 算法缺省取全局配置 csssl.root-ca.algorithm（新租户"开租户默认参数"约定）。
     */
    public CaTenant create(String tenantKey, String tenantName, String subjectO, String algorithm,
                        String contact, String description) {
        KeyVaultConfig.TenantVaultResolver.checkTenantKey(tenantKey);
        if (CaTenant.LEGACY_TENANT_KEY.equals(tenantKey)) {
            throw new IllegalArgumentException("default 为存量单根兼容标识，不可新建");
        }
        if (tenantName == null || tenantName.isBlank() || tenantName.trim().length() > 64) {
            throw new IllegalArgumentException("租户名称必填且不超过64字符");
        }
        if (subjectO == null || subjectO.isBlank() || subjectO.trim().length() > 128) {
            throw new IllegalArgumentException("证书主体O必填且不超过128字符");
        }
        String alg = algorithm == null || algorithm.isBlank()
                ? props.getRootCa().getAlgorithm() : algorithm.trim();
        CertUtil.checkAlgorithm(alg);
        if (findByKey(tenantKey) != null) {
            throw new IllegalArgumentException("租户标识已存在: " + tenantKey);
        }
        if (tenantMapper.selectCount(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getSubjectO, subjectO.trim())) > 0) {
            throw new IllegalArgumentException("证书主体O已被其他租户占用: " + subjectO);
        }

        CaTenant tenant = new CaTenant();
        tenant.setTenantKey(tenantKey);
        tenant.setTenantName(tenantName.trim());
        tenant.setSubjectO(subjectO.trim());
        tenant.setAlgorithm(alg);
        tenant.setStatus(STATUS_ACTIVE);
        tenant.setContact(contact);
        tenant.setDescription(description);
        tenant.setCreatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        log.info("[租户] 已登记租户 {}（{}，主体O={}，算法={}）", tenantKey, tenant.getTenantName(), tenant.getSubjectO(), alg);

        // 开租户即建根：独立根CA → SUB_CA → 门限份额（复用带 tenantKey 的初始化路径，幂等）
        caService.initRoot(tenantKey);

        // 平台唯一在营租户即主租户：无租户参数入口（审计/兼容调用）归位到它，无需重启
        Long actives = tenantMapper.selectCount(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getStatus, STATUS_ACTIVE));
        if (actives != null && actives == 1) {
            CaTenant.PRIMARY_TENANT_KEY = tenantKey;
        }
        return tenant;
    }

    /**
     * 暂停租户（ACTIVE → SUSPENDED）
     */
    public CaTenant suspend(String tenantKey) {
        return updateStatus(tenantKey, STATUS_SUSPENDED, List.of(STATUS_ACTIVE));
    }

    /**
     * 恢复租户（SUSPENDED → ACTIVE）
     */
    public CaTenant resume(String tenantKey) {
        return updateStatus(tenantKey, STATUS_ACTIVE, List.of(STATUS_SUSPENDED));
    }

    /**
     * 关闭租户（SUSPENDED → CLOSED；CLOSED 为终态，密钥清理由后续波次处置）
     */
    public CaTenant close(String tenantKey) {
        return updateStatus(tenantKey, STATUS_CLOSED, List.of(STATUS_ACTIVE, STATUS_SUSPENDED));
    }

    private CaTenant updateStatus(String tenantKey, String target, List<String> fromAllowed) {
        CaTenant tenant = get(tenantKey);
        if (!fromAllowed.contains(tenant.getStatus())) {
            throw new IllegalArgumentException("租户当前状态（" + tenant.getStatus() + "）不允许变更为 " + target);
        }
        tenant.setStatus(target);
        tenantMapper.updateById(tenant);
        log.info("[租户] {} 状态变更为 {}", tenantKey, target);
        return tenant;
    }
}
