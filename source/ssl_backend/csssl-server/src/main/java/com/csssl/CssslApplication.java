package com.csssl;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.Security;

/**
 * CA根认证中心（csssl）启动类。
 * 为 HIS 生态（cslk / csmig / csmis）提供根CA、证书签发、吊销CRL与国密加密传输支撑。
 *
 * <p>模块化后 mapper 分布在不同模块，这里必须把所有 mapper 包显式列全：
 * {@code com.csssl.mapper}（common/ca）与 {@code com.csssl.workflow.mapper}（workflow）。</p>
 */
@SpringBootApplication
@MapperScan({"com.csssl.mapper", "com.csssl.workflow.mapper"})
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
