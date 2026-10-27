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

    /** 证书与私钥落盘目录（私钥加密后落盘，不进数据库） */
    private String storeDir = "./data/certs";

    private RootCa rootCa = new RootCa();

    private Issue issue = new Issue();

    private SubCa subCa = new SubCa();

    private KeyVault keyVault = new KeyVault();

    /** 对外公开基础地址（用于证书 CDP/AIA 扩展、OCSP URL 生成），如 http://his-ca.example.com/ssl */
    private String publicBaseUrl = "http://localhost:8092/ssl";

    /** 私钥下载开关：开发期允许管理后台下载私钥，生产建议关闭 */
    private boolean allowPrivateKeyDownload = true;

    private Security security = new Security();

    @Data
    public static class RootCa {
        /** 根CA证书CN */
        private String cn = "CSSSL Root CA";
        /** 根CA算法：RSA2048 / SM2（国密） */
        private String algorithm = "RSA2048";
        /** 根证书有效期（天） */
        private int validityDays = 3650;
    }

    @Data
    public static class Issue {
        /** 签发默认有效期（天） */
        private int defaultValidityDays = 365;
    }

    @Data
    public static class SubCa {
        /** 服务签发二级CA的CN */
        private String cn = "CSSSL SubCA - Server Signing";
        /** 二级CA有效期（天） */
        private int validityDays = 730;
        /** 二级CA到期前多少天自动轮换（用根签新SUB_CA） */
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

    @Data
    public static class Sm2 {
        /** 16进制SM2私钥（64 hex字符）；留空则启动时临时生成一对（重启失效） */
        private String privateKey = "";
        /** 16进制SM2公钥（含04前缀，130 hex字符）；留空则从私钥推导 */
        private String publicKey = "";
    }

    @Data
    public static class Jwt {
        /** JWT签名密钥 */
        private String secret = "csssl-ca-jwt-secret-change-me-in-prod-2026";
        /** 会话有效期（毫秒），默认24小时 */
        private long expiration = 86400000L;
    }
}
