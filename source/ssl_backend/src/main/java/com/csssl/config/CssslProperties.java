package com.csssl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * csssl 业务配置（证书落盘目录、根CA/二级CA参数、密钥加密、签发默认值、登录鉴权密钥）。
 * 对应 application.yml 的 csssl.* 配置段，生产期通过环境变量覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "csssl")
public class CssslProperties {

    /**
     * 证书/CRL/根密钥份额落盘目录；私钥密文已改落库 ssl_key_vault（SM4），store-dir 只承载
     * master.key、公开材料与门限份额
     */
    private String storeDir = "./data/certs";

    private RootCa rootCa = new RootCa();

    private Issue issue = new Issue();

    private SubCa subCa = new SubCa();

    private KeyVault keyVault = new KeyVault();

    /**
     * 对外公开基础地址（用于证书 CDP/AIA 扩展、OCSP URL 生成），如 http://his-ca.example.com/ssl
     */
    private String publicBaseUrl = "http://localhost:8092/ssl";

    /**
     * 私钥下载开关：开发期允许管理后台下载私钥，生产建议关闭
     */
    private boolean allowPrivateKeyDownload = true;

    /**
     * OCSP 响应 nextUpdate 时效（小时），依赖方据此决定缓存刷新周期。
     * 行业通行 24~168h（1~7天，360 信创根计划：响应最长 7 天、至少每 4 天更新）；默认 24h。
     */
    private int ocspNextUpdateHours = 24;

    private Security security = new Security();

    private Api api = new Api();

    @Data
    public static class RootCa {
        /**
         * 根CA证书CN
         */
        private String cn = "CSSSL Root CA";
        /**
         * 根CA算法：RSA2048 / SM2（国密）
         */
        private String algorithm = "RSA2048";
        /**
         * 根证书有效期（天）
         */
        private int validityDays = 3650;
    }

    @Data
    public static class Issue {
        /**
         * 签发默认有效期（天）
         */
        private int defaultValidityDays = 365;
    }

    @Data
    public static class SubCa {
        /**
         * 服务签发二级CA的CN
         */
        private String cn = "CSSSL SubCA - Server Signing";
        /**
         * 二级CA有效期（天）
         */
        private int validityDays = 730;
        /**
         * 二级CA到期前多少天自动轮换（用根签新SUB_CA）
         */
        private int renewBeforeDays = 30;
    }

    @Data
    public static class KeyVault {
        /**
         * 私钥加密主密钥（SM4，32位十六进制=16字节）。
         * 生产必须通过环境变量 SSL_MASTER_KEY 注入；未配置时首次启动生成随机密钥落盘
         * store-dir/master.key（仅本机可读，多实例需共享或显式配置）。
         */
        private String masterKey = "";
    }

    @Data
    public static class Security {
        private Sm2 sm2 = new Sm2();
        private Jwt jwt = new Jwt();
    }

    /**
     * 外部系统直连 API（/ca/api/v1）：HMAC 鉴权、防重放、请求体与限流约束。
     * 租户由 appId 绑定关系反查，URL 不携带租户标识。
     */
    @Data
    public static class Api {
        /**
         * 总开关：关闭时 /ca/api/** 一律 503
         */
        private boolean enabled = true;
        /**
         * 请求时间戳允许偏差（秒）
         */
        private int clockSkewSeconds = 300;
        /**
         * nonce 保留时长（秒），期间同 appId 重复即拒
         */
        private int nonceTtlSeconds = 600;
        /**
         * 请求体上限（字节）
         */
        private int maxBodyBytes = 262144;
        /**
         * 单客户端每分钟请求上限（≤0 关闭限流）
         */
        private int rateLimitPerMinute = 120;
    }

    @Data
    public static class Sm2 {
        /**
         * 16进制SM2私钥（64 hex字符）；留空则启动时临时生成一对（重启失效）
         */
        private String privateKey = "";
        /**
         * 16进制SM2公钥（含04前缀，130 hex字符）；留空则从私钥推导
         */
        private String publicKey = "";
    }

    @Data
    public static class Jwt {
        /**
         * JWT签名密钥
         */
        private String secret = "csssl-ca-jwt-secret-change-me-in-prod-2026";
        /**
         * 会话有效期（毫秒），默认24小时
         */
        private long expiration = 86400000L;
    }
}
