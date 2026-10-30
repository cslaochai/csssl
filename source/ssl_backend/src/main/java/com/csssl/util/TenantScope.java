package com.csssl.util;

import com.csssl.entity.CaTenant;

/**
 * 租户数据域口径（唯一入口，安全红线不设旁路）。
 *
 * <p>操作者租户由调用方经 {@code UserUtils.requireLoginUser().getTenantKey()} 取得后传入，
 * 本类是纯函数口径，不感知登录态。null/空 operatorTenant=全局用户（跨租户账号）；非空=租户用户。</p>
 */
public final class TenantScope {

    private TenantScope() {
    }

    /**
     * 租户数据域统一口径（所有租户域操作唯一入口）：
     * <ul>
     *   <li>租户用户（本人 tenant_key 非空）→ 一律用本租户；请求显式 tenantKey 与本人租户不一致直接 403；</li>
     *   <li>全局用户（tenant_key 为空）→ 用请求显式 tenantKey，缺省 default 域。</li>
     * </ul>
     * 即：不存在"全部租户"的写旁路，跨租户写必须逐租户显式传参且缺省只落 default。
     */
    public static String effectiveTenant(String operatorTenant, String reqTenantKey) {
        String req = reqTenantKey == null || reqTenantKey.isBlank() ? null : reqTenantKey.trim();
        if (operatorTenant != null && !operatorTenant.isBlank()) {
            if (req != null && !req.equals(operatorTenant)) {
                throw new SecurityException("权限不足：不能操作其他租户数据");
            }
            return operatorTenant;
        }
        return req == null ? CaTenant.PRIMARY_TENANT_KEY : req;
    }

    /**
     * 敏感操作口径（CA 密钥域操作、证书签发、申请流转等落错域不可逆的端点）：
     * 全局用户必须显式传 tenantKey，不传直接抛错——绝不默默落 default 域执行。
     * 租户用户与 {@link #effectiveTenant} 一致（天然锁本租户）。
     */
    public static String requireExplicitTenant(String operatorTenant, String reqTenantKey) {
        String req = reqTenantKey == null || reqTenantKey.isBlank() ? null : reqTenantKey.trim();
        if (operatorTenant == null || operatorTenant.isBlank()) {
            if (req == null) {
                throw new IllegalArgumentException("全局账号必须显式指定租户域（tenantKey）");
            }
            return req;
        }
        return effectiveTenant(operatorTenant, reqTenantKey);
    }

    /**
     * 租户数据域"读"口径（列表/查询类端点）：
     * 租户用户强制本租户过滤（显式传别租户 403）；全局用户传 tenantKey=按租户过滤，
     * 不传=null 表示跨租户全量只读——这是唯一允许的跨租户读口（监督/总览职能，只读放行）。
     */
    public static String readTenantScope(String operatorTenant, String reqTenantKey) {
        if (operatorTenant != null && !operatorTenant.isBlank()) {
            return effectiveTenant(operatorTenant, reqTenantKey);
        }
        return reqTenantKey == null || reqTenantKey.isBlank() ? null : reqTenantKey.trim();
    }

    /**
     * 行级"读"校验：租户用户仅可访问本租户数据行（不属本租户→403）；
     * 全局用户放行（跨租户只读为总览/监督职能的合规设计）。rowTenant 空按 default 归位。
     */
    public static void requireRowVisible(String operatorTenant, String rowTenantKey) {
        if (operatorTenant == null || operatorTenant.isBlank()) {
            return;
        }
        String row = tenantOfRow(rowTenantKey);
        if (!operatorTenant.equals(row)) {
            throw new SecurityException("权限不足：不能访问其他租户数据");
        }
    }

    /**
     * 行级"写"校验：操作必须落在操作者数据域（effectiveTenant 与数据行归属租户一致，否则 403）。
     * 租户用户经 effectiveTenant 天然锁本租户；全局用户缺省只落 default，写他租户必须显式传 tenantKey。
     */
    public static String requireWriteTenant(String operatorTenant, String reqTenantKey, String rowTenantKey) {
        String eff = effectiveTenant(operatorTenant, reqTenantKey);
        if (!eff.equals(tenantOfRow(rowTenantKey))) {
            throw new SecurityException("权限不足：不能操作其他租户数据");
        }
        return eff;
    }

    /**
     * 数据行归属租户兜底：tenant_key 列 NOT NULL DEFAULT 'default'，历史语义空=默认域
     */
    public static String tenantOfRow(String rowTenantKey) {
        return rowTenantKey == null || rowTenantKey.isBlank() ? CaTenant.PRIMARY_TENANT_KEY : rowTenantKey;
    }
}
