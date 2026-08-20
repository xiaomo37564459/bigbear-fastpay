package com.fastpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 管理员"账号设置"端到端测试。
 * 覆盖：查资料、改账号（判重 + 当前密码校验）、改密码（旧密码校验 + 强度 + 两次一致）、
 *      改完之后旧 token 失效、用新密码能重新登录。
 *
 * 用 zonky embedded-postgres 拉一个真实 PostgreSQL 实例（不需要 Docker），
 * 建表用 db/init-pg.sql 新版本（不再预置默认管理员），管理员由 InitConfig 在启动时按 dev yml
 * 里的 admin / 123456 建出来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminAccountManagementTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String adminToken;
    // 用一个和默认强度规则都过得去、但又不属于常见弱口令黑名单的新密码
    private static final String NEW_PASSWORD = "NewPass2026";
    // 改账号用的新账号，走邮箱格式，同时验证 username 字段能装下邮箱（老字段 50 字符，新字段 100）
    private static final String NEW_USERNAME = "test-admin@example.com";

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
    @Order(1)
    void step1_loginAndFetchProfile() throws Exception {
        JsonNode login = dataOf(postJson("/api/admin/login", null,
                Map.of("username", "admin", "password", "123456")));
        adminToken = login.get("token").asText();
        assertThat(adminToken).isNotBlank();

        JsonNode profile = dataOf(getJson("/api/admin/profile", adminToken));
        assertThat(profile.get("username").asText()).isEqualTo("admin");
        // profile 不应回传密码
        assertThat(profile.has("password")).isFalse();
    }

    @Test
    @Order(2)
    void step2_profileEndpointsRejectAnonymous() throws Exception {
        // 未登录不能访问 profile 系列接口
        mockMvc.perform(get("/api/admin/profile"))
                .andExpect(mvcResult -> {
                    JsonNode body = objectMapper.readTree(mvcResult.getResponse().getContentAsByteArray());
                    assertThat(body.get("code").asInt()).isEqualTo(401);
                });
    }

    @Test
    @Order(3)
    void step3_updateUsernameRequiresCorrectCurrentPassword() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newUsername", NEW_USERNAME);
        body.put("currentPassword", "wrong-password");
        JsonNode err = errorOf(putJson("/api/admin/profile/username", adminToken, body));
        assertThat(err.get("code").asInt()).isEqualTo(400);
        assertThat(err.get("message").asText()).contains("当前密码不正确");
    }

    @Test
    @Order(4)
    void step4_updateUsernameSuccessAndOldTokenInvalidated() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newUsername", NEW_USERNAME);
        body.put("currentPassword", "123456");
        JsonNode data = dataOf(putJson("/api/admin/profile/username", adminToken, body));
        String newToken = data.get("token").asText();
        assertThat(newToken).isNotBlank().isNotEqualTo(adminToken);
        assertThat(data.get("username").asText()).isEqualTo(NEW_USERNAME);

        // 老 token 立即失效
        JsonNode err = errorOf(getJson("/api/admin/profile", adminToken));
        assertThat(err.get("code").asInt()).isEqualTo(401);

        // 新 token 立即可用
        adminToken = newToken;
        JsonNode profile = dataOf(getJson("/api/admin/profile", adminToken));
        assertThat(profile.get("username").asText()).isEqualTo(NEW_USERNAME);
    }

    @Test
    @Order(5)
    void step5_updatePasswordRejectsWrongOldPassword() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", "wrong-old");
        body.put("newPassword", NEW_PASSWORD);
        body.put("confirmPassword", NEW_PASSWORD);
        JsonNode err = errorOf(putJson("/api/admin/profile/password", adminToken, body));
        assertThat(err.get("code").asInt()).isEqualTo(400);
        assertThat(err.get("message").asText()).contains("旧密码不正确");
    }

    @Test
    @Order(6)
    void step6_updatePasswordRejectsWeakPassword() throws Exception {
        // 只有数字，长度也不够 8 位
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", "123456");
        body.put("newPassword", "123");
        body.put("confirmPassword", "123");
        JsonNode err = errorOf(putJson("/api/admin/profile/password", adminToken, body));
        assertThat(err.get("code").asInt()).isEqualTo(400);
        assertThat(err.get("message").asText()).contains("密码长度");

        // 长度够了但没有字母
        body.put("newPassword", "12345678");
        body.put("confirmPassword", "12345678");
        JsonNode err2 = errorOf(putJson("/api/admin/profile/password", adminToken, body));
        assertThat(err2.get("code").asInt()).isEqualTo(400);
    }

    @Test
    @Order(7)
    void step7_updatePasswordRejectsMismatchedConfirm() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", "123456");
        body.put("newPassword", NEW_PASSWORD);
        body.put("confirmPassword", NEW_PASSWORD + "_diff");
        JsonNode err = errorOf(putJson("/api/admin/profile/password", adminToken, body));
        assertThat(err.get("code").asInt()).isEqualTo(400);
        assertThat(err.get("message").asText()).contains("两次");
    }

    @Test
    @Order(8)
    void step8_updatePasswordSuccessAndOldTokenInvalidated() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", "123456");
        body.put("newPassword", NEW_PASSWORD);
        body.put("confirmPassword", NEW_PASSWORD);
        JsonNode data = dataOf(putJson("/api/admin/profile/password", adminToken, body));
        String newToken = data.get("token").asText();
        assertThat(newToken).isNotBlank().isNotEqualTo(adminToken);

        // 老 token 立即失效
        JsonNode err = errorOf(getJson("/api/admin/profile", adminToken));
        assertThat(err.get("code").asInt()).isEqualTo(401);

        adminToken = newToken;
    }

    @Test
    @Order(9)
    void step9_loginWithNewPasswordSucceedsAndOldPasswordFails() throws Exception {
        // 用旧密码已经登不进
        JsonNode fail = errorOf(postJson("/api/admin/login", null,
                Map.of("username", NEW_USERNAME, "password", "123456")));
        assertThat(fail.get("code").asInt()).isNotEqualTo(200);

        // 用新密码可以登进
        JsonNode data = dataOf(postJson("/api/admin/login", null,
                Map.of("username", NEW_USERNAME, "password", NEW_PASSWORD)));
        assertThat(data.get("token").asText()).isNotBlank();
    }

    @Test
    @Order(10)
    void step10_passwordPolicyEndpointReturnsRuleDescription() throws Exception {
        JsonNode policy = dataOf(getJson("/api/admin/profile/password-policy", adminToken));
        assertThat(policy.get("minLength").asInt()).isEqualTo(8);
        assertThat(policy.get("description").asText()).contains("字母").contains("数字");
    }

    private MvcResult postJson(String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult putJson(String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getJson(String url, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.get("code").asInt())
                .as("expected code=200 for %s, got body=%s", result.getRequest().getRequestURI(), body.toString())
                .isEqualTo(200);
        return body.get("data");
    }

    private JsonNode errorOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
