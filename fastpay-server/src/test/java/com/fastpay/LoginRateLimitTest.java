package com.fastpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastpay.config.LoginLimitProperties;
import com.fastpay.util.PasswordHasher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MTM-162 登录限次的端到端行为测试。
 * <p>
 * 覆盖以下场景（管理后台、商户后台各来一遍主链路）：
 * <ol>
 *   <li>连续输错阈值-1 次仍然可以输，第 阈值 次触发 429 锁定</li>
 *   <li>输错几次之后用正确密码登进，counter 应清零，再输错要重新数</li>
 *   <li>锁定期间即使密码正确也拒绝</li>
 *   <li>锁定期过了自动放行（用手动 UPDATE 让 locked_until 过期，避免真等 15 分钟）</li>
 *   <li>「服务重启」= 表里记录还在 = 锁定还在（直接断言表状态）</li>
 *   <li>IP 白名单命中时，同一 IP 大量失败不锁 IP 维度</li>
 *   <li>错误消息带有剩余次数提示（issue 里的具体要求）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LoginRateLimitTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    private static final String ADMIN_USERNAME = "rate_admin";
    private static final String MERCHANT_USERNAME = "rate_merchant";
    private static final String RAW_PASSWORD = "CorrectPass2026!";
    private static final String CLIENT_IP = "203.0.113.42";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LoginLimitProperties limitProps;

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
        // 白名单配一个专门的测试用假地址，用它触发白名单分支
        registry.add("fastpay.login-limit.ip-whitelist", () -> "198.51.100.99");
    }

    @BeforeEach
    void cleanState() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement s = conn.createStatement()) {
            s.execute("DELETE FROM fp_login_attempt");
            s.execute("DELETE FROM fp_admin WHERE username LIKE 'rate_%'");
            s.execute("DELETE FROM fp_merchant WHERE username LIKE 'rate_%'");
        }
        seedAdmin(ADMIN_USERNAME, RAW_PASSWORD);
        seedMerchant(MERCHANT_USERNAME, RAW_PASSWORD, "RATELIMIT001");
    }

    // ================= 管理后台 =================

    @Test
    void adminLogin_wrongPassword_atThreshold_thenLocked() throws Exception {
        int max = limitProps.getUserMaxAttempts();

        for (int attempt = 1; attempt < max; attempt++) {
            JsonNode body = jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-" + attempt, CLIENT_IP));
            assertThat(body.get("code").asInt())
                    .as("第 %s 次错密码还没锁", attempt)
                    .isEqualTo(500);
            int remaining = max - attempt;
            assertThat(body.get("message").asText())
                    .as("错密码要提示剩余次数（issue 明确要求）")
                    .contains("还可以尝试 " + remaining + " 次");
        }

        // 第 max 次：这次错完就应该达到阈值，counter=max，本次请求返回错密码 + 「还剩 0 次」的分支不会走到
        // 我们直接断言：这次错完，再来一次任意登录都被 429 挡下
        JsonNode last = jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-final", CLIENT_IP));
        assertThat(last.get("code").asInt()).isEqualTo(500); // 密码错走的还是密码错分支
        JsonNode locked = jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, CLIENT_IP));
        assertThat(locked.get("code").asInt())
                .as("被锁后即使密码正确也要拒绝")
                .isEqualTo(429);
        assertThat(locked.get("message").asText()).contains("登录尝试过多");
    }

    @Test
    void adminLogin_successResetsCounter() throws Exception {
        // 先错两次
        jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-1", CLIENT_IP));
        jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-2", CLIENT_IP));

        // 正确密码登进 —— counter 应清零
        JsonNode ok = jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, CLIENT_IP));
        assertThat(ok.get("code").asInt()).isEqualTo(200);

        // 再错一次，提示的剩余次数应该重新从 (max-1) 开始，而不是接着数
        JsonNode wrongAfter = jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-again", CLIENT_IP));
        int max = limitProps.getUserMaxAttempts();
        assertThat(wrongAfter.get("message").asText())
                .as("成功登录后应该清零")
                .contains("还可以尝试 " + (max - 1) + " 次");
    }

    @Test
    void adminLogin_lockedUntilInPast_isReleased() throws Exception {
        // 用错密码打到锁定
        int max = limitProps.getUserMaxAttempts();
        for (int i = 0; i < max; i++) {
            jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-" + i, CLIENT_IP));
        }
        // 确认真的锁上了
        assertThat(jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, CLIENT_IP)).get("code").asInt())
                .isEqualTo(429);

        // 把 locked_until 手工推到过去，模拟锁定期已过
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE fp_login_attempt SET locked_until = ? WHERE scope='admin'")) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
            ps.executeUpdate();
        }

        // 应该可以正常登录，登录成功还会 registerSuccess 把状态清干净
        JsonNode ok = jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, CLIENT_IP));
        assertThat(ok.get("code").asInt()).isEqualTo(200);
    }

    @Test
    void adminLogin_lockStatePersistedAcrossRestart() throws Exception {
        int max = limitProps.getUserMaxAttempts();
        for (int i = 0; i < max; i++) {
            jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-" + i, CLIENT_IP));
        }
        // 「服务重启后限制状态还在」= 表里那条记录仍然携带 locked_until in future
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT locked_until, fail_count FROM fp_login_attempt WHERE scope='admin' AND identity_key = ?")) {
            ps.setString(1, ADMIN_USERNAME.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("locked_until")).isNotNull();
                assertThat(rs.getTimestamp("locked_until").toLocalDateTime()).isAfter(LocalDateTime.now());
                assertThat(rs.getInt("fail_count")).isGreaterThanOrEqualTo(max);
            }
        }
    }

    @Test
    void adminLogin_ipWhitelist_doesNotLockIpDimension() throws Exception {
        String whitelistedIp = "198.51.100.99";
        // 用白名单 IP 打两个不同账号，每个都错 IP 阈值/2 次，累计超过 IP 阈值
        int ipMax = limitProps.getIpMaxAttempts();
        for (int i = 0; i < ipMax + 3; i++) {
            String user = "noaccount_" + i;
            jsonOf(loginAdmin(user, "whatever", whitelistedIp));
        }

        // 白名单 IP：换一个从未失败过的账号，用正确密码应能登进（IP 维度未锁）
        JsonNode ok = jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, whitelistedIp));
        assertThat(ok.get("code").asInt())
                .as("白名单 IP 不该被 IP 维度锁住")
                .isEqualTo(200);

        // 反向对照：**非白名单 IP** 打同样次数的不存在账号，IP 维度必被锁；此时哪怕换成正确账号也拒
        String normalIp = "203.0.113.77";
        for (int i = 0; i < ipMax + 1; i++) {
            String user = "noaccount_ip_" + i;
            jsonOf(loginAdmin(user, "whatever", normalIp));
        }
        JsonNode locked = jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, normalIp));
        assertThat(locked.get("code").asInt())
                .as("非白名单 IP 打满阈值就锁 IP 维度")
                .isEqualTo(429);
        assertThat(locked.get("message").asText()).contains("网络登录尝试过多");
    }

    @Test
    void adminLogin_unknownAccount_alsoCountsToRateLimit() throws Exception {
        // 攻击者枚举账号名的场景：账号根本不存在也要计入限次，不然可以先枚举出「存在的账号」再打密码
        int max = limitProps.getUserMaxAttempts();
        String unknownAccount = "definitely_not_an_account";
        for (int i = 0; i < max; i++) {
            jsonOf(loginAdmin(unknownAccount, "whatever", "203.0.113.55"));
        }
        JsonNode locked = jsonOf(loginAdmin(unknownAccount, "whatever", "203.0.113.55"));
        assertThat(locked.get("code").asInt())
                .as("不存在的账号也要限次")
                .isEqualTo(429);
    }

    // ================= 商户后台 =================

    @Test
    void merchantLogin_wrongPassword_atThreshold_thenLocked() throws Exception {
        int max = limitProps.getUserMaxAttempts();
        for (int attempt = 1; attempt < max; attempt++) {
            JsonNode body = jsonOf(loginMerchant(MERCHANT_USERNAME, "wrong-" + attempt, CLIENT_IP));
            assertThat(body.get("code").asInt()).isEqualTo(500);
            assertThat(body.get("message").asText()).contains("还可以尝试");
        }
        jsonOf(loginMerchant(MERCHANT_USERNAME, "wrong-final", CLIENT_IP));

        JsonNode locked = jsonOf(loginMerchant(MERCHANT_USERNAME, RAW_PASSWORD, CLIENT_IP));
        assertThat(locked.get("code").asInt()).isEqualTo(429);
        assertThat(locked.get("message").asText()).contains("登录尝试过多");
    }

    @Test
    void merchantLogin_successResetsCounter() throws Exception {
        jsonOf(loginMerchant(MERCHANT_USERNAME, "bad", CLIENT_IP));
        JsonNode ok = jsonOf(loginMerchant(MERCHANT_USERNAME, RAW_PASSWORD, CLIENT_IP));
        assertThat(ok.get("code").asInt()).isEqualTo(200);

        // 表里 fail_count 应清零
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT fail_count FROM fp_login_attempt WHERE scope='merchant' AND identity_key = ?")) {
            ps.setString(1, MERCHANT_USERNAME.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    assertThat(rs.getInt("fail_count")).isZero();
                }
            }
        }
    }

    @Test
    void merchantAndAdmin_lockedIndependently() throws Exception {
        // 把 admin 打到锁定
        int max = limitProps.getUserMaxAttempts();
        for (int i = 0; i < max; i++) {
            jsonOf(loginAdmin(ADMIN_USERNAME, "wrong-" + i, CLIENT_IP));
        }
        assertThat(jsonOf(loginAdmin(ADMIN_USERNAME, RAW_PASSWORD, CLIENT_IP)).get("code").asInt())
                .isEqualTo(429);

        // 商户后台的同名账号不应受影响（scope 隔离）
        // MERCHANT_USERNAME 跟 ADMIN_USERNAME 恰好是不同名，但更严的验证是：即使账号名相同两 scope 也应独立
        // 这里换用真的商户账号 MERCHANT_USERNAME 走一次成功登录来验证
        // （因为 scope 不同，counter/lock 都在不同行）
        JsonNode merchant = jsonOf(loginMerchant(MERCHANT_USERNAME, RAW_PASSWORD, CLIENT_IP));
        assertThat(merchant.get("code").asInt())
                .as("商户后台不受管理后台被锁的影响")
                .isEqualTo(200);
    }

    // ================= 工具方法 =================

    private void seedAdmin(String username, String rawPassword) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO fp_admin (username, password, nickname, status, token_version) " +
                             "VALUES (?, ?, ?, 1, 0)")) {
            ps.setString(1, username);
            ps.setString(2, PasswordHasher.hash(rawPassword));
            ps.setString(3, "限次测试管理员");
            ps.executeUpdate();
        }
    }

    private void seedMerchant(String username, String rawPassword, String merchantNo) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO fp_merchant (merchant_no, merchant_name, username, password, " +
                             "api_key, api_secret, status) VALUES (?, ?, ?, ?, ?, ?, 1)")) {
            ps.setString(1, merchantNo);
            ps.setString(2, "限次测试商户");
            ps.setString(3, username);
            ps.setString(4, PasswordHasher.hash(rawPassword));
            ps.setString(5, "rate-key-" + merchantNo);
            ps.setString(6, "rate-secret-" + merchantNo);
            ps.executeUpdate();
        }
    }

    private MvcResult loginAdmin(String username, String password, String clientIp) throws Exception {
        return postJsonWithIp("/api/admin/login", Map.of("username", username, "password", password), clientIp);
    }

    private MvcResult loginMerchant(String username, String password, String clientIp) throws Exception {
        return postJsonWithIp("/api/merchant/login", Map.of("username", username, "password", password), clientIp);
    }

    private MvcResult postJsonWithIp(String url, Object body, String clientIp) throws Exception {
        return mockMvc.perform(post(url)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
