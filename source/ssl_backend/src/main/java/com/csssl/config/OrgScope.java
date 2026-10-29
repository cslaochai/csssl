package com.csssl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaOrg;
import com.csssl.mapper.CaOrgMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主机构校准：启动时把「无机构参数入口」的兜底机构指向在营机构（按 id 取首个），
 * 必须先于 CaService 的启动兜底初始化执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrgScope implements ApplicationRunner {

    private final CaOrgMapper orgMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<CaOrg> actives = orgMapper.selectList(new LambdaQueryWrapper<CaOrg>()
                .eq(CaOrg::getStatus, "ACTIVE")
                .orderByAsc(CaOrg::getId));
        if (actives.isEmpty()) {
            log.warn("[机构] 尚无在营机构，兼容入口暂指向 {}，请在机构管理创建首个机构", CaOrg.PRIMARY_ORG_KEY);
            return;
        }
        CaOrg primary = actives.get(0);
        CaOrg.PRIMARY_ORG_KEY = primary.getOrgKey();
        if (actives.size() > 1) {
            log.warn("[机构] 在营机构 {} 个，无机构参数入口归位首个机构 {}", actives.size(), primary.getOrgKey());
        } else {
            log.info("[机构] 主机构={}（{}）", primary.getOrgKey(), primary.getOrgName());
        }
    }
}
