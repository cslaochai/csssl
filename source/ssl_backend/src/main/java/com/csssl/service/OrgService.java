package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.config.CssslProperties;
import com.csssl.config.KeyVaultConfig;
import com.csssl.entity.CaOrg;
import com.csssl.mapper.CaOrgMapper;
import com.csssl.util.CertUtil;
import com.csssl.vo.CaStatusVO;
import com.csssl.vo.OrgVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 机构域管理（路线A：每家医院=一套独立根CA，机构间互不信任）。
 * 开机构即建根：登记记录后立即复用 {@link CaService} 带 orgKey 的初始化路径生成
 * 独立根CA+SUB_CA+Shamir(3,5) 门限份额；suspend/close 本波仅落状态字段，
 * CLOSED 拒绝签发由后续波次在签发/CRL/OCSP 链路 enforce。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CLOSED = "CLOSED";
    /**
     * 机构标识格式：小写字母数字（同时兜住路径穿越——orgKey 直接参与 store-dir/orgs/{orgKey} 拼路）
     */
    private static final Pattern ORG_KEY = Pattern.compile("^[a-z0-9]{1,32}$");
    private final CaOrgMapper orgMapper;
    private final CaService caService;
    private final CssslProperties props;

    /**
     * 机构列表（含各机构根CA根状态摘要，管理页一屏看全根池拓扑）
     */
    public List<OrgVO> overview() {
        List<OrgVO> rows = new ArrayList<>();
        for (CaOrg org : list()) {
            rows.add(buildRow(org));
        }
        return rows;
    }

    /**
     * 机构分页查询（管理页：关键字命中 标识/名称/主体O，状态精确匹配；根状态仅对当前页计算）
     */
    public Page<OrgVO> page(String keyword, String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<CaOrg> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(CaOrg::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(CaOrg::getOrgKey, kw).or().like(CaOrg::getOrgName, kw).or().like(CaOrg::getSubjectO, kw));
        }
        qw.orderByAsc(CaOrg::getId);
        Page<CaOrg> page = orgMapper.selectPage(new Page<>(pageNo, pageSize), qw);
        return (Page<OrgVO>) page.convert(this::buildRow);
    }

    /**
     * 单机构状态摘要（详情/创建回执用）
     */
    public OrgVO overviewOf(String orgKey) {
        return buildRow(get(orgKey));
    }

    private OrgVO buildRow(CaOrg org) {
        OrgVO vo = OrgVO.from(org);
        try {
            CaStatusVO st = caService.status(org.getOrgKey());
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
            // 单机构状态异常不拖垮整个列表（如密钥文件损坏）
            vo.setHasRoot(false);
            vo.setStatusError(e.getMessage());
        }
        return vo;
    }

    /**
     * 机构原始记录列表
     */
    public List<CaOrg> list() {
        return orgMapper.selectList(new LambdaQueryWrapper<CaOrg>().orderByAsc(CaOrg::getId));
    }

    /**
     * 按 orgKey 查询（不存在返回 null）
     */
    public CaOrg findByKey(String orgKey) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        return orgMapper.selectOne(new LambdaQueryWrapper<CaOrg>().eq(CaOrg::getOrgKey, orgKey));
    }

    /**
     * 按 orgKey 查询（不存在抛异常）
     */
    public CaOrg get(String orgKey) {
        CaOrg org = findByKey(orgKey);
        if (org == null) {
            throw new IllegalArgumentException("机构不存在: " + orgKey);
        }
        return org;
    }

    /**
     * 校验机构存在且 ACTIVE（根CA管理写操作前置；default 域存量语义不受机构表约束）
     */
    public void requireActive(String orgKey) {
        CaOrg org = get(orgKey);
        if (!STATUS_ACTIVE.equals(org.getStatus())) {
            throw new IllegalArgumentException("机构状态不允许该操作: " + orgKey + "（" + org.getStatus() + "）");
        }
    }

    /**
     * 新建机构并立即初始化独立根CA+SUB_CA+门限份额。
     * 算法缺省取全局配置 csssl.root-ca.algorithm（新机构"开机构默认参数"约定）。
     */
    public CaOrg create(String orgKey, String orgName, String subjectO, String algorithm,
                        String contact, String description) {
        KeyVaultConfig.OrgVaultResolver.checkOrgKey(orgKey);
        if (CaOrg.LEGACY_ORG_KEY.equals(orgKey)) {
            throw new IllegalArgumentException("default 为存量单根兼容标识，不可新建");
        }
        if (orgName == null || orgName.isBlank() || orgName.trim().length() > 64) {
            throw new IllegalArgumentException("机构名称必填且不超过64字符");
        }
        if (subjectO == null || subjectO.isBlank() || subjectO.trim().length() > 128) {
            throw new IllegalArgumentException("证书主体O必填且不超过128字符");
        }
        String alg = algorithm == null || algorithm.isBlank()
                ? props.getRootCa().getAlgorithm() : algorithm.trim();
        CertUtil.checkAlgorithm(alg);
        if (findByKey(orgKey) != null) {
            throw new IllegalArgumentException("机构标识已存在: " + orgKey);
        }
        if (orgMapper.selectCount(new LambdaQueryWrapper<CaOrg>()
                .eq(CaOrg::getSubjectO, subjectO.trim())) > 0) {
            throw new IllegalArgumentException("证书主体O已被其他机构占用: " + subjectO);
        }

        CaOrg org = new CaOrg();
        org.setOrgKey(orgKey);
        org.setOrgName(orgName.trim());
        org.setSubjectO(subjectO.trim());
        org.setAlgorithm(alg);
        org.setStatus(STATUS_ACTIVE);
        org.setContact(contact);
        org.setDescription(description);
        org.setCreatedAt(LocalDateTime.now());
        orgMapper.insert(org);
        log.info("[机构] 已登记机构 {}（{}，主体O={}，算法={}）", orgKey, org.getOrgName(), org.getSubjectO(), alg);

        // 开机构即建根：独立根CA → SUB_CA → 门限份额（复用带 orgKey 的初始化路径，幂等）
        caService.initRoot(orgKey);

        // 平台唯一在营机构即主机构：无机构参数入口（审计/兼容调用）归位到它，无需重启
        Long actives = orgMapper.selectCount(new LambdaQueryWrapper<CaOrg>()
                .eq(CaOrg::getStatus, STATUS_ACTIVE));
        if (actives != null && actives == 1) {
            CaOrg.PRIMARY_ORG_KEY = orgKey;
        }
        return org;
    }

    /**
     * 暂停机构（ACTIVE → SUSPENDED）
     */
    public CaOrg suspend(String orgKey) {
        return updateStatus(orgKey, STATUS_SUSPENDED, List.of(STATUS_ACTIVE));
    }

    /**
     * 恢复机构（SUSPENDED → ACTIVE）
     */
    public CaOrg resume(String orgKey) {
        return updateStatus(orgKey, STATUS_ACTIVE, List.of(STATUS_SUSPENDED));
    }

    /**
     * 关闭机构（SUSPENDED → CLOSED；CLOSED 为终态，密钥清理由后续波次处置）
     */
    public CaOrg close(String orgKey) {
        return updateStatus(orgKey, STATUS_CLOSED, List.of(STATUS_ACTIVE, STATUS_SUSPENDED));
    }

    private CaOrg updateStatus(String orgKey, String target, List<String> fromAllowed) {
        CaOrg org = get(orgKey);
        if (!fromAllowed.contains(org.getStatus())) {
            throw new IllegalArgumentException("机构当前状态（" + org.getStatus() + "）不允许变更为 " + target);
        }
        org.setStatus(target);
        orgMapper.updateById(org);
        log.info("[机构] {} 状态变更为 {}", orgKey, target);
        return org;
    }
}
