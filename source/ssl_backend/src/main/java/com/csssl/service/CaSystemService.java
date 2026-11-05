package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.dto.CaSystemCreateDTO;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CaSystem;
import com.csssl.entity.LoginUser;
import com.csssl.mapper.CaSystemMapper;
import com.csssl.vo.CaSystemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公司下的「系统」管理：系统名称即证书主体 O，公司内唯一。
 *
 * <p>系统目前是证书域的归属标签与接入凭证的归属单元；根 CA 仍归公司（不动），
 * 二级 CA 下沉到系统由后续阶段完成。为避免破坏存量数据，系统名不做强关联外键，
 * 历史行的 system_name 允许为 NULL（表示存量/默认系统）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaSystemService {

    private final CaSystemMapper systemMapper;
    private final CompanyService companyService;
    private final AuditService auditService;

    /**
     * 该公司下的全部系统名（按 id 升序，即创建时间正序）。
     */
    public List<String> listSystemNames(String companyUscc) {
        if (companyUscc == null || companyUscc.isBlank()) {
            throw new IllegalArgumentException("参数 companyUscc 不能为空");
        }
        String company = companyUscc.trim();
        return systemMapper.selectList(new LambdaQueryWrapper<CaSystem>()
                .eq(CaSystem::getCompanyUscc, company)
                .orderByAsc(CaSystem::getId))
                .stream()
                .map(CaSystem::getSystemName)
                .toList();
    }

    /**
     * 该公司首个系统名（按 id 升序）；一个系统都没有时，用公司主体 O 补建一个并返回（存量公司兼容）。
     */
    public String firstSystemName(String companyUscc) {
        if (companyUscc == null || companyUscc.isBlank()) {
            throw new IllegalArgumentException("参数 companyUscc 不能为空");
        }
        String company = companyUscc.trim();
        List<String> names = listSystemNames(company);
        if (!names.isEmpty()) {
            return names.get(0);
        }
        CaCompany companyRow = companyService.findByUscc(company);
        String fallback = companyRow == null ? null : companyRow.getSubjectO();
        if (fallback == null || fallback.isBlank()) {
            throw new IllegalArgumentException("该公司尚无系统且缺少主体O，无法定位系统，请先新增系统");
        }
        CaSystem created = getOrCreate(company, fallback, "存量公司自动补建的首个系统", null, null);
        return created.getSystemName();
    }

    /**
     * 该公司下的全部系统（按创建时间正序，首个即建公司时创建的系统）。
     */
    public List<CaSystemVO> listByCompany(String companyUscc) {
        if (companyUscc == null || companyUscc.isBlank()) {
            throw new IllegalArgumentException("参数 companyUscc 不能为空");
        }
        String company = companyUscc.trim();
        List<CaSystem> rows = systemMapper.selectList(new LambdaQueryWrapper<CaSystem>()
                .eq(CaSystem::getCompanyUscc, company)
                .orderByAsc(CaSystem::getId));
        String companyName = companyName(company);
        List<CaSystemVO> out = new ArrayList<>(rows.size());
        for (CaSystem s : rows) {
            CaSystemVO vo = CaSystemVO.from(s);
            vo.setCompanyName(companyName);
            out.add(vo);
        }
        return out;
    }

    /**
     * 新增系统（公司内重名直接拒绝）。
     */
    public CaSystemVO create(CaSystemCreateDTO dto, LoginUser actor, String ip) {
        String company = requireCompany(dto.getCompanyUscc());
        String name = normalizeName(dto.getSystemName());
        if (exists(company, name)) {
            throw new IllegalArgumentException("该公司下已存在同名系统：" + name);
        }
        CaSystem row = insert(company, name, dto.getDescription(), actor);
        auditService.record(actor, "SYSTEM_CREATE", name,
                "新增系统：" + name + "（公司=" + company + "，主体O=" + name + "）", ip, company);
        log.info("[系统] 已新增 company={} system={}", company, name);
        CaSystemVO vo = CaSystemVO.from(row);
        vo.setCompanyName(companyName(company));
        return vo;
    }

    /**
     * 幂等取系统：存在直接返回，不存在则创建。建公司时创建首个系统、以及存量数据补齐都走这里。
     */
    public CaSystem getOrCreate(String companyUscc, String systemName, String description,
                                LoginUser actor, String ip) {
        String company = requireCompany(companyUscc);
        String name = normalizeName(systemName);
        CaSystem existing = find(company, name);
        if (existing != null) {
            return existing;
        }
        return insert(company, name, description, actor);
    }

    /**
     * 取该公司首个系统；一个系统都没有时，用公司主体 O 补建一个（存量公司兼容）。
     */
    public CaSystem firstOrCreateForCompany(String companyUscc, LoginUser actor, String ip) {
        String company = requireCompany(companyUscc);
        List<CaSystem> rows = systemMapper.selectList(new LambdaQueryWrapper<CaSystem>()
                .eq(CaSystem::getCompanyUscc, company)
                .orderByAsc(CaSystem::getId)
                .last("LIMIT 1"));
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        CaCompany companyRow = companyService.findByUscc(company);
        String fallback = companyRow == null ? null : companyRow.getSubjectO();
        if (fallback == null || fallback.isBlank()) {
            throw new IllegalArgumentException("该公司尚无系统且缺少主体O，无法自动补建，请先新增系统");
        }
        return getOrCreate(company, fallback, "存量公司自动补建的首个系统", actor, ip);
    }

    /**
     * 登记接入客户端时校验系统存在（系统名是证书域的业务键）。
     */
    public CaSystem requireByName(String companyUscc, String systemName) {
        String company = companyUscc == null ? null : companyUscc.trim();
        String name = normalizeName(systemName);
        CaSystem row = find(company, name);
        if (row == null) {
            throw new IllegalArgumentException("系统不存在：" + name + "（公司=" + company + "），请先新增系统");
        }
        if (row.getStatus() != null && row.getStatus() != 1) {
            throw new IllegalArgumentException("系统已停用：" + name);
        }
        return row;
    }

    private CaSystem insert(String company, String name, String description, LoginUser actor) {
        CaSystem row = new CaSystem();
        row.setCompanyUscc(company);
        row.setSystemName(name);
        row.setStatus(1);
        row.setDescription(description == null || description.isBlank() ? null : description.trim());
        row.setCreatedAt(LocalDateTime.now());
        if (actor != null) {
            row.setCreatedByUserId(actor.getUserId());
            row.setCreatedByName(actor.getRealName());
        }
        systemMapper.insert(row);
        return row;
    }

    private boolean exists(String company, String name) {
        return find(company, name) != null;
    }

    private CaSystem find(String company, String name) {
        return systemMapper.selectOne(new LambdaQueryWrapper<CaSystem>()
                .eq(CaSystem::getCompanyUscc, company)
                .eq(CaSystem::getSystemName, name)
                .last("LIMIT 1"));
    }

    private String requireCompany(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("必须指定所属公司");
        }
        String company = raw.trim();
        if (!CaCompany.LEGACY_NAMESPACE.equals(company)) {
            CaCompany row = companyService.findByUscc(company);
            if (row == null) {
                throw new IllegalArgumentException("公司不存在：" + company);
            }
        }
        return company;
    }

    private String companyName(String companyUscc) {
        CaCompany row = companyService.findByUscc(companyUscc);
        return row == null ? null : row.getCompanyName();
    }

    /**
     * 系统名会用于后续拼 vault 的 rel_dir 与证书 DN 的 O，禁止路径分隔符与控制字符。
     */
    private static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("系统名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > 128) {
            throw new IllegalArgumentException("系统名称不超过128字符");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalArgumentException("系统名称不能包含路径分隔符或控制字符");
        }
        return name;
    }
}
