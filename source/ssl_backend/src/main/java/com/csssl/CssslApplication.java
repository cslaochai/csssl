package com.csssl;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.Security;

/**
 * CA根认证中心（csssl）启动类。
 * 为 HIS 生态（cslk / csmig / csmis）提供根CA、证书签发、吊销CRL与国密加密传输支撑。
 */
@SpringBootApplication
@MapperScan("com.csssl.mapper")
public class CssslApplication {

    static {
        // 注册 BouncyCastle provider：RSA/SM2/SM3withSM2/CRL 等 X.509 操作依赖
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(CssslApplication.class, args);
    }
}
