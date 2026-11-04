package com.csssl.util;

/**
 * 公司数据域口径（唯一入口，安全红线不设旁路）。
 *
 * <p>用户不再有归属公司（{@code ssl_sys_user.company_uscc} 已下线），操作哪家公司一律由请求参数显式声明：
 * 写操作必须指名道姓，读操作不传即跨公司全量（监督/总览职能）。跨公司的边界由角色权限点收口，
 * 而不是由账号挂在哪个公司收口。</p>
 */
public final class CompanyScope {

    private CompanyScope() {
    }

    /**
     * 公司数据域统一口径（所有公司域操作唯一入口）：显式传参=该公司，缺省=全局默认域（default）。
     */
    public static String effectiveCompany(String reqCompanyUscc) {
        String req = reqCompanyUscc == null || reqCompanyUscc.isBlank() ? null : reqCompanyUscc.trim();
        return req == null ? "default" : req;
    }

    /**
     * 敏感操作口径（CA 密钥域操作、证书签发、申请流转等落错域不可逆的端点）：
     * 必须显式传 companyUscc，不传直接抛错——绝不默默落默认域执行。
     */
    public static String requireExplicitCompany(String reqCompanyUscc) {
        String req = reqCompanyUscc == null || reqCompanyUscc.isBlank() ? null : reqCompanyUscc.trim();
        if (req == null) {
            throw new IllegalArgumentException("必须显式指定公司域（companyUscc）");
        }
        return req;
    }

    /**
     * 公司数据域"读"口径（列表/查询类端点）：传 companyUscc=按公司过滤，
     * 不传=null 表示跨公司全量只读——这是唯一允许的跨公司读口（监督/总览职能，只读放行）。
     */
    public static String readCompanyScope(String reqCompanyUscc) {
        return reqCompanyUscc == null || reqCompanyUscc.isBlank() ? null : reqCompanyUscc.trim();
    }

    /**
     * 行级"写"校验：操作必须落在请求声明的公司域内（声明的公司与数据行归属公司不一致→403）。
     * 不存在"全部公司"的写旁路，跨公司写必须逐公司显式传参。
     */
    public static String requireWriteCompany(String reqCompanyUscc, String rowCompanyUscc) {
        String eff = effectiveCompany(reqCompanyUscc);
        if (!eff.equals(companyOfRow(rowCompanyUscc))) {
            throw new SecurityException("权限不足：不能操作其他公司数据");
        }
        return eff;
    }

    /**
     * 数据行归属公司兜底：company_uscc 列 NOT NULL DEFAULT 'default'，历史语义空=默认域
     */
    public static String companyOfRow(String rowCompanyUscc) {
        return rowCompanyUscc == null || rowCompanyUscc.isBlank() ? "default" : rowCompanyUscc;
    }
}
