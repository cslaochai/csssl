package com.csssl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaTenant;
import com.csssl.mapper.CaTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主租户校准：启动时把「无租户参数入口」的兜底租户指向在营租户（按 id 取首个），
 * 必须先于 CaService 的启动兜底初始化执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantScope implements ApplicationRunner {

    private final CaTenantMapper tenantMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<CaTenant> actives = tenantMapper.selectList(new LambdaQueryWrapper<CaTenant>()
                .eq(CaTenant::getStatus, "ACTIVE")
                .orderByAsc(CaTenant::getId));
        if (actives.isEmpty()) {
            log.warn("[租户] 尚无在营租户，兼容入口暂指向 {}，请在租户管理创建首个租户", CaTenant.PRIMARY_TENANT_KEY);
            return;
        }
        CaTenant primary = actives.get(0);
        CaTenant.PRIMARY_TENANT_KEY = primary.getTenantKey();
        if (actives.size() > 1) {
            log.warn("[租户] 在营租户 {} 个，无租户参数入口归位首个租户 {}", actives.size(), primary.getTenantKey());
        } else {
            log.info("[租户] 主租户={}（{}）", primary.getTenantKey(), primary.getTenantName());
        }
    }
}
