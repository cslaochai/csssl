package com.csssl.util;

import com.csssl.entity.CaTenant;

/**
 * 租户数据域口径（唯一入口，安全红线不设旁路）。
 *
 * <p>操作者租户由调用方经 {@code UserUtils.requireLoginUser().getTenantUscc()} 取得后传入，
 * 本类是纯函数口径，不感知登录态。null/空 operatorTenant=全局用户（跨租户账号）；非空=租户用户。</p>
 */
public final class TenantScope {

    private TenantScope() {
    }

    /**
     * 租户数据域统一口径（所有租户域操作唯一入口）：
     * <ul>
     *   <li>租户用户（本人 tenant_uscc 非空）→ 一律用本租户；请求显式 tenantUscc 与本人租户不一致直接 403；</li>
     *   <li>全局用户（tenant_uscc 为空）→ 用请求显式 tenantUscc，缺省 default 域。</li>
     * </ul>
     * 即：不存在"全部租户"的写旁路，跨租户写必须逐租户显式传参且缺省只落 default。
     */
    public static String effectiveTenant(String operatorTenant, String reqTenantUscc) {
        String req = reqTenantUscc == null || reqTenantUscc.isBlank() ? null : reqTenantUscc.trim();
        if (operatorTenant != null && !operatorTenant.isBlank()) {
            if (req != null && !req.equals(operatorTenant)) {
                throw new SecurityException("权限不足：不能操作其他租户数据");
            }
            return operatorTenant;
        }
        return req == null ? CaTenant.PRIMARY_TENANT_USCC : req;
    }

    /**
     * 敏感操作口径（CA 密钥域操作、证书签发、申请流转等落错域不可逆的端点）：
     * 全局用户必须显式传 tenantUscc，不传直接抛错——绝不默默落 default 域执行。
     * 租户用户与 {@link #effectiveTenant} 一致（天然锁本租户）。
     */
    public static String requireExplicitTenant(String operatorTenant, String reqTenantUscc) {
        String req = reqTenantUscc == null || reqTenantUscc.isBlank() ? null : reqTenantUscc.trim();
        if (operatorTenant == null || operatorTenant.isBlank()) {
            if (req == null) {
                throw new IllegalArgumentException("全局账号必须显式指定租户域（tenantUscc）");
            }
            return req;
        }
        return effectiveTenant(operatorTenant, reqTenantUscc);
    }

    /**
     * 租户数据域"读"口径（列表/查询类端点）：
     * 租户用户强制本租户过滤（显式传别租户 403）；全局用户传 tenantUscc=按租户过滤，
     * 不传=null 表示跨租户全量只读——这是唯一允许的跨租户读口（监督/总览职能，只读放行）。
     */
    public static String readTenantScope(String operatorTenant, String reqTenantUscc) {
        if (operatorTenant != null && !operatorTenant.isBlank()) {
            return effectiveTenant(operatorTenant, reqTenantUscc);
        }
        return reqTenantUscc == null || reqTenantUscc.isBlank() ? null : reqTenantUscc.trim();
    }

    /**
     * 行级"读"校验：租户用户仅可访问本租户数据行（不属本租户→403）；
     * 全局用户放行（跨租户只读为总览/监督职能的合规设计）。rowTenant 空按 default 归位。
     */
    public static void requireRowVisible(String operatorTenant, String rowTenantUscc) {
        if (operatorTenant == null || operatorTenant.isBlank()) {
            return;
        }
        String row = tenantOfRow(rowTenantUscc);
        if (!operatorTenant.equals(row)) {
            throw new SecurityException("权限不足：不能访问其他租户数据");
        }
    }

    /**
     * 行级"写"校验：操作必须落在操作者数据域（effectiveTenant 与数据行归属租户一致，否则 403）。
     * 租户用户经 effectiveTenant 天然锁本租户；全局用户缺省只落 default，写他租户必须显式传 tenantUscc。
     */
    public static String requireWriteTenant(String operatorTenant, String reqTenantUscc, String rowTenantUscc) {
        String eff = effectiveTenant(operatorTenant, reqTenantUscc);
        if (!eff.equals(tenantOfRow(rowTenantUscc))) {
            throw new SecurityException("权限不足：不能操作其他租户数据");
        }
        return eff;
    }

    /**
     * 数据行归属租户兜底：tenant_uscc 列 NOT NULL DEFAULT 'default'，历史语义空=默认域
     */
    public static String tenantOfRow(String rowTenantUscc) {
        return rowTenantUscc == null || rowTenantUscc.isBlank() ? CaTenant.PRIMARY_TENANT_USCC : rowTenantUscc;
    }
}
