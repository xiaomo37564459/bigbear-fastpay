package com.fastpay.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * 锁死 CORS 白名单的行为：
 *   1) 只放行配置里明确写出来的前端地址
 *   2) 不允许再出现「*」通配 —— 通配 + 允许带凭证 = 所有网站都能带用户 cookie 调接口
 *   3) 空配置不许静默启动
 *
 * 这个类是纯单测，不拉 Spring 上下文；AuthInterceptor 只在拦截器注册时用到，
 * CORS 这条路径不会碰它，所以构造函数里塞 null 是安全的。
 */
class WebConfigCorsTest {

    private static final AuthInterceptor UNUSED = new AuthInterceptor(null, null);

    /** CorsRegistry#getCorsConfigurations 是 protected，测试子类专门开个窗口读一下 */
    static class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> readAll() {
            return getCorsConfigurations();
        }
    }

    @Test
    void configuredOriginsAreRegisteredExactly() {
        WebConfig config = new WebConfig(UNUSED,
                List.of("https://pay.copliot.cloud", "http://localhost:3001"));

        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        config.addCorsMappings(registry);

        Map<String, CorsConfiguration> configs = registry.readAll();
        CorsConfiguration cfg = configs.get("/**");
        assertThat(cfg).as("必须为 /** 注册一条 CORS 规则").isNotNull();
        assertThat(cfg.getAllowedOrigins())
                .as("只放行白名单里的地址，顺序保持配置一致")
                .containsExactly("https://pay.copliot.cloud", "http://localhost:3001");
        assertThat(cfg.getAllowedOriginPatterns())
                .as("不许再用 allowedOriginPatterns 走通配路径")
                .isNull();
        assertThat(cfg.getAllowCredentials()).as("带凭证仍然放行").isTrue();
        assertThat(cfg.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    @Test
    void wildcardOriginIsRejectedAtStartup() {
        WebConfig config = new WebConfig(UNUSED,
                List.of("https://pay.copliot.cloud", "*"));
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        assertThatIllegalStateException()
                .isThrownBy(() -> config.addCorsMappings(registry))
                .withMessageContaining("通配");
    }

    @Test
    void wildcardOriginPatternIsAlsoRejected() {
        // allowedOriginPatterns 支持模式匹配（如 https://*.example.com），本身不是安全问题，
        // 但纯 "*" 通配等价于旧的敞开配置，一样必须拦掉
        WebConfig config = new WebConfig(UNUSED, List.of("*"));
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        assertThatIllegalStateException()
                .isThrownBy(() -> config.addCorsMappings(registry))
                .withMessageContaining("通配");
    }

    @Test
    void emptyOriginsAreRejectedAtStartup() {
        WebConfig config = new WebConfig(UNUSED, List.of());
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        assertThatIllegalStateException()
                .isThrownBy(() -> config.addCorsMappings(registry))
                .withMessageContaining("未配置");
    }

    @Test
    void blankOriginsAreRejectedAsEmpty() {
        // 环境变量写成 "  " 或 ","（人为失误）都不能被当成有效白名单
        WebConfig config = new WebConfig(UNUSED, List.of("   "));
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        assertThatIllegalStateException()
                .isThrownBy(() -> config.addCorsMappings(registry))
                .withMessageContaining("未配置");
    }
}
