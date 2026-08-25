package com.fastpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MTM-162 复检后又主干合并了 MTM-246（同一份 AdminServiceImpl.java 也动了 150+ 行）。
 * 沈执要求「把绕过那条命令再打一次」确认合并后限次还挡得住。
 * <p>
 * 这个测试跟 QA 复检时那条 curl 循环一模一样：起 Tomcat 用真实 socket 收请求，
 * 每次换一个假 X-Forwarded-For 前缀但尾部同一个 IP（模拟 nginx 的 $proxy_add_x_forwarded_for），
 * 打 10 次以后应当被 IP 维度锁死。MockMvc 覆盖过一遍，这里再用真实 HTTP client 兜一遍，
 * 确认 Tomcat 层不会因为主干新加的东西把这条路径搞坏。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class LoginRateLimitHttpSmokeTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startDatabase() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        jdbcUrl = pg.getJdbcUrl("postgres", "postgres");
        try (Connection conn = pg.getPostgresDatabase().getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/init-pg.sql"), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Test
    void bypassAttemptViaFakedXff_blockedAt11thShotOnRealSocket() throws Exception {
        String realBackendIp = "203.0.113.99";

        // 前 10 枪：每次 X-Forwarded-For 都不一样但都以 realBackendIp 结尾（就是 nginx 那种拼接效果）
        // 老实现每次都会拿到一把新钥匙、永远锁不住；ClientIpResolver 只取最后一段，会稳定命中同一个 IP。
        for (int i = 1; i <= 10; i++) {
            String xff = String.format("9.9.%d.%d, %s", i, i, realBackendIp);
            ResponseEntity<String> resp = post(xff, "target_" + i, "wrong-pass");
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK); // 业务错误也是 HTTP 200 + body.code=500
        }

        // 第 11 枪：应当被 IP 维度挡下
        ResponseEntity<String> blocked = post("8.8.8.8, " + realBackendIp, "target_11", "wrong-pass");
        JsonNode body = objectMapper.readTree(blocked.getBody());
        assertThat(body.get("code").asInt())
                .as("第 11 枪必须被挡下，主干合并 MTM-246 之后 IP 限次不能失效。响应体：%s", body)
                .isEqualTo(429);
        assertThat(body.get("message").asText()).contains("网络登录尝试过多");
    }

    private ResponseEntity<String> post(String xff, String username, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", xff);
        String url = "http://localhost:" + port + "/fastpay-server/api/admin/login";
        String bodyJson = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(bodyJson, headers), String.class);
    }
}
