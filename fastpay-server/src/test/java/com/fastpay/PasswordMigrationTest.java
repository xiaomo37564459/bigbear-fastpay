package com.fastpay;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 密码存法迁移（MTM-161：MD5 -> bcrypt）的过渡行为测试。
 *
 * 覆盖三种情况，管理员、商户两条线各跑一遍：
 * 1. 老格式（MD5）账号还能正常登录
 * 2. 登录成功后，数据库里那条记录确实变成了新格式（bcrypt）
 * 3. 变成新格式之后，再登录一次照样能登
 *
 * 老格式密码是直接绕过 Service 层用 JDBC 插到库里的——现在 AdminServiceImpl /
 * MerchantServiceImpl 建号、改密码都只会写 bcrypt，模拟"迁移前就已经存在的老账号"只能这样造。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PasswordMigrationTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    private static final String LEGACY_ADMIN_USERNAME = "legacy_md5_admin";
    private static final String LEGACY_MERCHANT_USERNAME = "legacy_md5_merchant";
    private static final String RAW_PASSWORD = "LegacyPass2018";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

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
    void adminWithLegacyMd5Password_canLogin_getsUpgradedToBcrypt_andStillLoginsAfter() throws Exception {
        seedLegacyAdmin(LEGACY_ADMIN_USERNAME, RAW_PASSWORD);

        // 1. 老格式（MD5）账号还能正常登录
        JsonNode firstLogin = dataOf(loginAdmin(LEGACY_ADMIN_USERNAME, RAW_PASSWORD));
        assertThat(firstLogin.get("token").asText()).isNotBlank();

        // 2. 登录成功后，库里这条记录确实变成了新格式（bcrypt）
        String storedPassword = readAdminPassword(LEGACY_ADMIN_USERNAME);
        assertThat(storedPassword).startsWith("$2");
        assertThat(storedPassword).isNotEqualTo(SecureUtil.md5(RAW_PASSWORD));

        // 3. 变成新格式之后，再登录一次照样能登
        JsonNode secondLogin = dataOf(loginAdmin(LEGACY_ADMIN_USERNAME, RAW_PASSWORD));
        assertThat(secondLogin.get("token").asText()).isNotBlank();

        // 升级前后原密码含义不变：错密码一直登不进
        JsonNode wrong = errorOf(loginAdmin(LEGACY_ADMIN_USERNAME, "totally-wrong-password"));
        assertThat(wrong.get("code").asInt()).isNotEqualTo(200);
    }

    @Test
    void merchantWithLegacyMd5Password_canLogin_getsUpgradedToBcrypt_andStillLoginsAfter() throws Exception {
        seedLegacyMerchant(LEGACY_MERCHANT_USERNAME, RAW_PASSWORD);

        // 1. 老格式（MD5）账号还能正常登录
        JsonNode firstLogin = dataOf(loginMerchant(LEGACY_MERCHANT_USERNAME, RAW_PASSWORD));
        assertThat(firstLogin.get("token").asText()).isNotBlank();

        // 2. 登录成功后，库里这条记录确实变成了新格式（bcrypt）
        String storedPassword = readMerchantPassword(LEGACY_MERCHANT_USERNAME);
        assertThat(storedPassword).startsWith("$2");
        assertThat(storedPassword).isNotEqualTo(SecureUtil.md5(RAW_PASSWORD));

        // 3. 变成新格式之后，再登录一次照样能登
        JsonNode secondLogin = dataOf(loginMerchant(LEGACY_MERCHANT_USERNAME, RAW_PASSWORD));
        assertThat(secondLogin.get("token").asText()).isNotBlank();

        JsonNode wrong = errorOf(loginMerchant(LEGACY_MERCHANT_USERNAME, "totally-wrong-password"));
        assertThat(wrong.get("code").asInt()).isNotEqualTo(200);
    }

    /**
     * MTM-195：商户密码从老格式升级成 bcrypt 之后，那条记录的 update_time 必须跟着更新。
     * 之前 QA 在 MTM-185 验收时发现：库里那条记录密码确实升级了，但 update_time 还停在
     * 老值，让人事后查"这条数据什么时候被动过"时被误导。
     *
     * 复现手段：先把一条老 MD5 商户塞进库、把 update_time 手工写成一个明显较早的时间，
     * 再走一次登录接口触发升级，然后读回 update_time 断言它一定比种下去的旧值新。
     */
    @Test
    void merchantPasswordUpgrade_alsoRefreshesUpdateTime() throws Exception {
        String username = "legacy_ts_merchant";
        LocalDateTime seededOldTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

        seedLegacyMerchantWithUpdateTime(username, RAW_PASSWORD, seededOldTime, "LEGACYNO0002");

        // 走一次登录 —— 应当触发老 MD5 -> bcrypt 的自动升级
        JsonNode login = dataOf(loginMerchant(username, RAW_PASSWORD));
        assertThat(login.get("token").asText()).isNotBlank();

        // 密码升级本身要成功（保底断言，避免我们只测了 update_time 却把升级本身改坏了）
        assertThat(readMerchantPassword(username)).startsWith("$2");

        // 核心断言：update_time 必须比之前种下去的旧值新
        LocalDateTime afterUpdateTime = readMerchantUpdateTime(username);
        assertThat(afterUpdateTime)
                .as("商户密码被自动升级后，update_time 应该跟着变（MTM-195）")
                .isAfter(seededOldTime);
    }

    /**
     * MTM-195 对照组：管理员那条本来就是正确的 —— 密码升级后 update_time 也要跟着变。
     * 加这条一起断言，防止将来有人改动通用填充逻辑时把这个"当前正确"的路径又改坏。
     */
    @Test
    void adminPasswordUpgrade_alsoRefreshesUpdateTime() throws Exception {
        String username = "legacy_ts_admin";
        LocalDateTime seededOldTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

        seedLegacyAdminWithUpdateTime(username, RAW_PASSWORD, seededOldTime);

        JsonNode login = dataOf(loginAdmin(username, RAW_PASSWORD));
        assertThat(login.get("token").asText()).isNotBlank();

        assertThat(readAdminPassword(username)).startsWith("$2");

        LocalDateTime afterUpdateTime = readAdminUpdateTime(username);
        assertThat(afterUpdateTime)
                .as("管理员密码被自动升级后，update_time 应该跟着变")
                .isAfter(seededOldTime);
    }

    private void seedLegacyAdmin(String username, String rawPassword) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO fp_admin (username, password, nickname, status, token_version) " +
                             "VALUES (?, ?, ?, 1, 0)")) {
            ps.setString(1, username);
            ps.setString(2, SecureUtil.md5(rawPassword));
            ps.setString(3, "迁移前老账号");
            ps.executeUpdate();
        }
    }

    private void seedLegacyMerchant(String username, String rawPassword) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO fp_merchant (merchant_no, merchant_name, username, password, " +
                             "api_key, api_secret, status) VALUES (?, ?, ?, ?, ?, ?, 1)")) {
            ps.setString(1, "LEGACYNO0001");
            ps.setString(2, "迁移前老商户");
            ps.setString(3, username);
            ps.setString(4, SecureUtil.md5(rawPassword));
            ps.setString(5, "legacy-api-key");
            ps.setString(6, "legacy-api-secret");
            ps.executeUpdate();
        }
    }

    private void seedLegacyAdminWithUpdateTime(String username, String rawPassword,
                                                LocalDateTime updateTime) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO fp_admin (username, password, nickname, status, token_version, " +
                             "create_time, update_time) VALUES (?, ?, ?, 1, 0, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, SecureUtil.md5(rawPassword));
            ps.setString(3, "迁移前老账号");
            ps.setTimestamp(4, Timestamp.valueOf(updateTime));
            ps.setTimestamp(5, Timestamp.valueOf(updateTime));
            ps.executeUpdate();
        }
    }

    private void seedLegacyMerchantWithUpdateTime(String username, String rawPassword,
                                                   LocalDateTime updateTime, String merchantNo) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO fp_merchant (merchant_no, merchant_name, username, password, " +
                             "api_key, api_secret, status, create_time, update_time) " +
                             "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)")) {
            ps.setString(1, merchantNo);
            ps.setString(2, "迁移前老商户");
            ps.setString(3, username);
            ps.setString(4, SecureUtil.md5(rawPassword));
            ps.setString(5, "legacy-api-key-" + username);
            ps.setString(6, "legacy-api-secret-" + username);
            ps.setTimestamp(7, Timestamp.valueOf(updateTime));
            ps.setTimestamp(8, Timestamp.valueOf(updateTime));
            ps.executeUpdate();
        }
    }

    private LocalDateTime readAdminUpdateTime(String username) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT update_time FROM fp_admin WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getTimestamp("update_time").toLocalDateTime();
            }
        }
    }

    private LocalDateTime readMerchantUpdateTime(String username) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT update_time FROM fp_merchant WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getTimestamp("update_time").toLocalDateTime();
            }
        }
    }

    private String readAdminPassword(String username) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT password FROM fp_admin WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString("password");
            }
        }
    }

    private String readMerchantPassword(String username) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT password FROM fp_merchant WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString("password");
            }
        }
    }

    private MvcResult loginAdmin(String username, String password) throws Exception {
        return postJson("/api/admin/login", Map.of("username", username, "password", password));
    }

    private MvcResult loginMerchant(String username, String password) throws Exception {
        return postJson("/api/merchant/login", Map.of("username", username, "password", password));
    }

    private MvcResult postJson(String url, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
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
