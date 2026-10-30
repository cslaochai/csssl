package com.csssl.util;

import com.csssl.entity.CaOrg;

/**
 * 机构数据域口径（唯一入口，安全红线不设旁路）。
 *
 * <p>操作者机构由调用方经 {@code UserUtils.requireLoginUser().getOrgKey()} 取得后传入，
 * 本类是纯函数口径，不感知登录态。null/空 operatorOrg=全局用户（跨机构账号）；非空=机构用户。</p>
 */
public final class OrgScope {

    private OrgScope() {
    }

    /**
     * 机构数据域统一口径（所有机构域操作唯一入口）：
     * <ul>
     *   <li>机构用户（本人 org_key 非空）→ 一律用本机构；请求显式 orgKey 与本人机构不一致直接 403；</li>
     *   <li>全局用户（org_key 为空）→ 用请求显式 orgKey，缺省 default 域。</li>
     * </ul>
     * 即：不存在"全部机构"的写旁路，跨机构写必须逐机构显式传参且缺省只落 default。
     */
    public static String effectiveOrg(String operatorOrg, String reqOrgKey) {
        String req = reqOrgKey == null || reqOrgKey.isBlank() ? null : reqOrgKey.trim();
        if (operatorOrg != null && !operatorOrg.isBlank()) {
            if (req != null && !req.equals(operatorOrg)) {
                throw new SecurityException("权限不足：不能操作其他机构数据");
            }
            return operatorOrg;
        }
        return req == null ? CaOrg.PRIMARY_ORG_KEY : req;
    }

    /**
     * 敏感操作口径（CA 密钥域操作、证书签发、申请流转等落错域不可逆的端点）：
     * 全局用户必须显式传 orgKey，不传直接抛错——绝不默默落 default 域执行。
     * 机构用户与 {@link #effectiveOrg} 一致（天然锁本机构）。
     */
    public static String requireExplicitOrg(String operatorOrg, String reqOrgKey) {
        String req = reqOrgKey == null || reqOrgKey.isBlank() ? null : reqOrgKey.trim();
        if (operatorOrg == null || operatorOrg.isBlank()) {
            if (req == null) {
                throw new IllegalArgumentException("全局账号必须显式指定机构域（orgKey）");
            }
            return req;
        }
        return effectiveOrg(operatorOrg, reqOrgKey);
    }

    /**
     * 机构数据域"读"口径（列表/查询类端点）：
     * 机构用户强制本机构过滤（显式传别机构 403）；全局用户传 orgKey=按机构过滤，
     * 不传=null 表示跨机构全量只读——这是唯一允许的跨机构读口（监督/总览职能，只读放行）。
     */
    public static String readOrgScope(String operatorOrg, String reqOrgKey) {
        if (operatorOrg != null && !operatorOrg.isBlank()) {
            return effectiveOrg(operatorOrg, reqOrgKey);
        }
        return reqOrgKey == null || reqOrgKey.isBlank() ? null : reqOrgKey.trim();
    }

    /**
     * 行级"读"校验：机构用户仅可访问本机构数据行（不属本机构→403）；
     * 全局用户放行（跨机构只读为总览/监督职能的合规设计）。rowOrg 空按 default 归位。
     */
    public static void requireRowVisible(String operatorOrg, String rowOrgKey) {
        if (operatorOrg == null || operatorOrg.isBlank()) {
            return;
        }
        String row = orgOfRow(rowOrgKey);
        if (!operatorOrg.equals(row)) {
            throw new SecurityException("权限不足：不能访问其他机构数据");
        }
    }

    /**
     * 行级"写"校验：操作必须落在操作者数据域（effectiveOrg 与数据行归属机构一致，否则 403）。
     * 机构用户经 effectiveOrg 天然锁本机构；全局用户缺省只落 default，写他机构必须显式传 orgKey。
     */
    public static String requireWriteOrg(String operatorOrg, String reqOrgKey, String rowOrgKey) {
        String eff = effectiveOrg(operatorOrg, reqOrgKey);
        if (!eff.equals(orgOfRow(rowOrgKey))) {
            throw new SecurityException("权限不足：不能操作其他机构数据");
        }
        return eff;
    }

    /**
     * 数据行归属机构兜底：org_key 列 NOT NULL DEFAULT 'default'，历史语义空=默认域
     */
    public static String orgOfRow(String rowOrgKey) {
        return rowOrgKey == null || rowOrgKey.isBlank() ? CaOrg.PRIMARY_ORG_KEY : rowOrgKey;
    }
}
