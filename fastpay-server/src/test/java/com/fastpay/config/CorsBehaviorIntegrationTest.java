package com.fastpay.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 白名单的行为级测试（MTM-177）：模拟浏览器带着不同 Origin 头调后端，
 * 验证「白名单内的前端拿到放行头、白名单外的网站拿不到」这件事端到端成立。
 *
 * 这条测试回答的是验收要问的那句话：管理后台 / 商户中心 / demo 还能不能正常调接口？
 *   - 线上前端 https://pay.copliot.cloud 在名单内 → 放行头会带回显
 *   - 本地 dev server http://localhost:3001 在名单内 → 放行头会带回显
 *   - 陌生网站 https://evil.example.com 不在名单内 → 拿不到放行头（浏览器端跨域请求即被拦）
 *
 * 用一个最小 MVC 上下文 + MockMvc：只装 WebConfig（CORS 规则）、一个探针接口和 Web MVC 必需的
 * 自动配置。不经过 FastPayApplication 的 @MapperScan（那会把数据库 mapper 硬拉进来），
 * 不碰数据库和业务 bean。探针路径 /api/ping 不在 AuthInterceptor 的拦截范围内。
 *
 * 注意：这里 @ImportAutoConfiguration 引入 WebMvcAutoConfiguration 不是可有可无的 ——
 * 只有它跑了（@EnableWebMvc），WebConfig#addCorsMappings 的白名单才会被收集进
 * RequestMappingHandlerMapping；缺了它 DispatcherServlet 会用一副没装过白名单的
 * 裸 handler mapping，测出来的 CORS 行为是假的。
 */
@SpringBootTest(classes = CorsBehaviorIntegrationTest.MvcSliceBoot.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "fastpay.cors.allowed-origins=https://pay.copliot.cloud,http://localhost:3001,http://localhost:3002"
})
class CorsBehaviorIntegrationTest {

    /** 最小启动配置：Web MVC 必需的自动配置 + 被测的 WebConfig + 探针接口 */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({WebConfig.class, PingController.class})
    static class MvcSliceBoot {
    }

    @Autowired
    private MockMvc mockMvc;

    /** WebConfig 构造注入需要；探针路径不在拦截范围，不会真正用到 */
    @MockBean
    private AuthInterceptor authInterceptor;

    /** 最小探针接口：任何来源都能打到的普通 GET，好单看 CORS 头的行为 */
    @RestController
    static class PingController {
        @GetMapping("/api/ping")
        String ping() {
            return "pong";
        }
    }

    @Test
    void productionFrontendOriginIsAllowed() throws Exception {
        mockMvc.perform(get("/api/ping").header("Origin", "https://pay.copliot.cloud"))
                .andExpect(status().isOk())
                // 白名单内的来源：响应头回显来源地址，且允许带凭证 —— 前端正常用不受影响
                .andExpect(header().string("Access-Control-Allow-Origin", "https://pay.copliot.cloud"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void localDevOriginsAreAllowed() throws Exception {
        // 本地两个前端 dev server（管理后台 3001 / 商户中心 3002）拉下代码直接跑就能调通
        mockMvc.perform(get("/api/ping").header("Origin", "http://localhost:3001"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3001"));
        mockMvc.perform(get("/api/ping").header("Origin", "http://localhost:3002"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3002"));
    }

    @Test
    void preflightFromAllowedOriginPasses() throws Exception {
        // 浏览器发跨域请求前的「预检」（OPTIONS + Access-Control-Request-Method）也要放行，
        // 否则带自定义头（如 Authorization）的请求第一步就卡死。
        // 带凭证模式下 Spring 按规范回显请求的具体头名（authorization），而不是 "*" ——
        // "*" 用在允许凭证的响应上是非法的，浏览器会拒收
        mockMvc.perform(options("/api/ping")
                        .header("Origin", "https://pay.copliot.cloud")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://pay.copliot.cloud"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", "authorization"));
    }

    @Test
    void unknownOriginGetsNoCorsApproval() throws Exception {
        // 陌生网站：直接被 403 拒掉（比「响应但缺放行头」更硬的拒绝）——
        // 浏览器根本拿不到响应内容，冒充用户调接口的路被堵死
        mockMvc.perform(get("/api/ping").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        // 陌生网站的预检同样被 403 拒绝，连真正的跨域请求都发不出来
        mockMvc.perform(options("/api/ping")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
