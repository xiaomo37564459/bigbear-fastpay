package com.fastpay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 配置类
 * 配置跨域、拦截器等
 *
 * @author xiaomo37564459
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * 前端白名单。以前是 allowedOriginPatterns("*") + allowCredentials(true) —— 任意网站
     * 都能带用户 cookie 调后端接口，MTM-177 收掉这个洞。
     * 具体地址走配置 / 环境变量 FASTPAY_CORS_ALLOWED_ORIGINS 注入，不写死在代码里。
     * 默认值分别写在 application-dev.yml / application-prod.yml。
     */
    private final List<String> allowedOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${fastpay.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 配置跨域
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> effective = normalize(allowedOrigins);
        if (effective.isEmpty()) {
            throw new IllegalStateException(
                    "fastpay.cors.allowed-origins 未配置。请设置环境变量 FASTPAY_CORS_ALLOWED_ORIGINS，"
                            + "多个域名用英文逗号分隔，例如：https://pay.copliot.cloud,http://localhost:3001");
        }
        for (String origin : effective) {
            if ("*".equals(origin)) {
                throw new IllegalStateException(
                        "fastpay.cors.allowed-origins 不能包含通配符 \"*\"："
                                + "这个后端要放行凭证 (allowCredentials=true)，"
                                + "通配等于所有网站都能带用户 cookie 调接口，等同 MTM-177 修复前的洞。");
            }
        }
        registry.addMapping("/**")
                .allowedOrigins(effective.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(s -> s != null && !s.trim().isEmpty())
                .map(String::trim)
                .toList();
    }

    /**
     * 配置拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/admin/**", "/api/merchant/**")
                .excludePathPatterns(
                        "/api/admin/login",
                        "/api/merchant/login",
                        "/api/pay/**",
                        // Knife4j 文档相关路径
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                );
    }
}
