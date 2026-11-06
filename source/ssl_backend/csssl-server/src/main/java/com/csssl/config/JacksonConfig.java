package com.csssl.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置：把 Long 类型（雪花 ID 等）序列化成字符串。
 *
 * <p>JavaScript 的 Number.MAX_SAFE_INTEGER = 2^53 - 1 ≈ 9.007e15，雪花 ID 是 19 位数字
 * （远超安全整数上限），直接 JSON 序列化成 number 传给前端会精度丢失、id 对不上。
 * 这里统一把所有 Long/long 都序列化成字符串，前端用 string 存就不会丢精度。</p>
 */
@Configuration
public class JacksonConfig {

    @org.springframework.context.annotation.Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
