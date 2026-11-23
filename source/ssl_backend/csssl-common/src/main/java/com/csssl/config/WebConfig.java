package com.csssl.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：登录态拦截与 /admin/** 鉴权已迁移到 Spring Security
 * （见 SecurityConfig / TokenAuthFilter）；此处仅保留跨域配置
 * （开发期放开，前端 vite 代理同源时无需 CORS）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发期放开；生产建议由网关统一处理或收紧为管理后台域名
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
