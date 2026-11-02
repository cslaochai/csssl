package com.csssl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaCompany;
import com.csssl.enums.CompanyStatusEnum;
import com.csssl.mapper.CompanyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主公司校准：启动时把「无公司参数入口」的兜底公司指向在营公司（按 id 取首个），
 * 必须先于 CaService 的启动兜底初始化执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CompanyScope implements ApplicationRunner {

    private final CompanyMapper companyMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<CaCompany> actives = companyMapper.selectList(new LambdaQueryWrapper<CaCompany>()
                .eq(CaCompany::getStatus, CompanyStatusEnum.ACTIVE.getCode())
                .orderByAsc(CaCompany::getId));
        if (actives.isEmpty()) {
            log.warn("[公司] 尚无在营公司，兼容入口暂指向 {}，请在公司管理创建首个公司", CaCompany.PRIMARY_COMPANY_USCC);
            return;
        }
        CaCompany primary = actives.get(0);
        CaCompany.PRIMARY_COMPANY_USCC = primary.getUscc();
        if (actives.size() > 1) {
            log.warn("[公司] 在营公司 {} 个，无公司参数入口归位首个公司 {}", actives.size(), primary.getUscc());
        } else {
            log.info("[公司] 主公司={}（{}）", primary.getUscc(), primary.getCompanyName());
        }
    }
}
