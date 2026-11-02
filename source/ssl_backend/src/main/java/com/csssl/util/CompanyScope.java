package com.csssl.util;

import com.csssl.entity.CaCompany;

/**
 * 公司数据域口径（唯一入口，安全红线不设旁路）。
 *
 * <p>操作者公司由调用方经 {@code UserUtils.requireLoginUser().getCompanyUscc()} 取得后传入，
 * 本类是纯函数口径，不感知登录态。null/空 operatorCompany=全局用户（跨公司账号）；非空=公司用户。</p>
 */
public final class CompanyScope {

    private CompanyScope() {
    }

    /**
     * 公司数据域统一口径（所有公司域操作唯一入口）：
     * <ul>
     *   <li>公司用户（本人 company_uscc 非空）→ 一律用本公司；请求显式 companyUscc 与本人公司不一致直接 403；</li>
     *   <li>全局用户（company_uscc 为空）→ 用请求显式 companyUscc，缺省 default 域。</li>
     * </ul>
     * 即：不存在"全部公司"的写旁路，跨公司写必须逐公司显式传参且缺省只落 default。
     */
    public static String effectiveCompany(String operatorCompany, String reqCompanyUscc) {
        String req = reqCompanyUscc == null || reqCompanyUscc.isBlank() ? null : reqCompanyUscc.trim();
        if (operatorCompany != null && !operatorCompany.isBlank()) {
            if (req != null && !req.equals(operatorCompany)) {
                throw new SecurityException("权限不足：不能操作其他公司数据");
            }
            return operatorCompany;
        }
        return req == null ? CaCompany.PRIMARY_COMPANY_USCC : req;
    }

    /**
     * 敏感操作口径（CA 密钥域操作、证书签发、申请流转等落错域不可逆的端点）：
     * 全局用户必须显式传 companyUscc，不传直接抛错——绝不默默落 default 域执行。
     * 公司用户与 {@link #effectiveCompany} 一致（天然锁本公司）。
     */
    public static String requireExplicitCompany(String operatorCompany, String reqCompanyUscc) {
        String req = reqCompanyUscc == null || reqCompanyUscc.isBlank() ? null : reqCompanyUscc.trim();
        if (operatorCompany == null || operatorCompany.isBlank()) {
            if (req == null) {
                throw new IllegalArgumentException("全局账号必须显式指定公司域（companyUscc）");
            }
            return req;
        }
        return effectiveCompany(operatorCompany, reqCompanyUscc);
    }

    /**
     * 公司数据域"读"口径（列表/查询类端点）：
     * 公司用户强制本公司过滤（显式传别公司 403）；全局用户传 companyUscc=按公司过滤，
     * 不传=null 表示跨公司全量只读——这是唯一允许的跨公司读口（监督/总览职能，只读放行）。
     */
    public static String readCompanyScope(String operatorCompany, String reqCompanyUscc) {
        if (operatorCompany != null && !operatorCompany.isBlank()) {
            return effectiveCompany(operatorCompany, reqCompanyUscc);
        }
        return reqCompanyUscc == null || reqCompanyUscc.isBlank() ? null : reqCompanyUscc.trim();
    }

    /**
     * 行级"读"校验：公司用户仅可访问本公司数据行（不属本公司→403）；
     * 全局用户放行（跨公司只读为总览/监督职能的合规设计）。rowCompany 空按 default 归位。
     */
    public static void requireRowVisible(String operatorCompany, String rowCompanyUscc) {
        if (operatorCompany == null || operatorCompany.isBlank()) {
            return;
        }
        String row = companyOfRow(rowCompanyUscc);
        if (!operatorCompany.equals(row)) {
            throw new SecurityException("权限不足：不能访问其他公司数据");
        }
    }

    /**
     * 行级"写"校验：操作必须落在操作者数据域（effectiveCompany 与数据行归属公司一致，否则 403）。
     * 公司用户经 effectiveCompany 天然锁本公司；全局用户缺省只落 default，写他公司必须显式传 companyUscc。
     */
    public static String requireWriteCompany(String operatorCompany, String reqCompanyUscc, String rowCompanyUscc) {
        String eff = effectiveCompany(operatorCompany, reqCompanyUscc);
        if (!eff.equals(companyOfRow(rowCompanyUscc))) {
            throw new SecurityException("权限不足：不能操作其他公司数据");
        }
        return eff;
    }

    /**
     * 数据行归属公司兜底：company_uscc 列 NOT NULL DEFAULT 'default'，历史语义空=默认域
     */
    public static String companyOfRow(String rowCompanyUscc) {
        return rowCompanyUscc == null || rowCompanyUscc.isBlank() ? CaCompany.PRIMARY_COMPANY_USCC : rowCompanyUscc;
    }
}
