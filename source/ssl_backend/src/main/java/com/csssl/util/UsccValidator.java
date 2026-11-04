package com.csssl.util;

import com.csssl.entity.CaCompany;

/**
 * 统一社会信用代码（USCC）校验：GB 32100-2015，18 位。
 *
 * <p>字符集不含 I / O / S / V / Z，共 31 个字符（0-9 与除 5 个外的 A-Z）；末位校验码
 * = (31 - 加权和 mod 31) mod 31，权重见 {@link #WEIGHTS}。本类从配置类下沉而来，
 * 让「公司标识合法性」这一领域规则有独立、可单测的归宿。</p>
 *
 * <p>除真实 USCC 外，本校验同时放行存量单根命名空间 {@link CaCompany#LEGACY_NAMESPACE}
 * （"default"），仅用于路径/目录兼容，并非真实编码。路径穿越字符（. / 等）因不在字符集内，
 * 天然被字符集兜底挡住。</p>
 */
public final class UsccValidator {

    private UsccValidator() {
    }

    /** GB 32100 字符集（不含 I/O/S/V/Z），索引即该位权值 */
    public static final String USCC_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    /** 前 17 位权重 */
    private static final int[] WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    /**
     * 严格校验 18 位统一社会信用代码（含校验码）。"default" 等非真实编码返回 false。
     */
    public static boolean isValid(String uscc) {
        if (uscc == null || uscc.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int idx = USCC_CHARS.indexOf(uscc.charAt(i));
            if (idx < 0) {
                return false;
            }
            sum += idx * WEIGHTS[i];
        }
        int expect = (31 - (sum % 31)) % 31;
        int actual = USCC_CHARS.indexOf(uscc.charAt(17));
        return actual == expect;
    }

    /**
     * 公司标识校验：接受合法 USCC 或存量单根命名空间 {@code default}；其他一律抛
     * IllegalArgumentException（同时兜底路径穿越字符 . / 等）。
     */
    public static void checkCompanyUscc(String companyUscc) {
        if (companyUscc == null) {
            throw new IllegalArgumentException("公司标识不能为空");
        }
        if (CaCompany.LEGACY_NAMESPACE.equals(companyUscc)) {
            return;
        }
        if (!isValid(companyUscc)) {
            throw new IllegalArgumentException("非法公司标识（须为18位统一社会信用代码，GB 32100）: " + companyUscc);
        }
    }
}
