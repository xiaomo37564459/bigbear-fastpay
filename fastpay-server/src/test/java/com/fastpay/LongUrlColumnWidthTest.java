package com.fastpay;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守住"URL 列不能再被缩回 VARCHAR(255)"（MTM-209 / MTM-151）。
 *
 * 背景：sub2api 会往 return_url 塞带 JWT 的长回跳地址，实测单条 344 字符。
 * 早期 fp_pay_order.notify_url / return_url 是 VARCHAR(255)，一插就报
 * "value too long for type character varying(255)"，接口 503，用户支付失败。
 * 2026-08-19 生产库手工加宽到 VARCHAR(2000)，init 脚本同步更新，但一直没把加宽
 * 动作沉淀成迁移脚本；V1_5 补上这个洞。
 *
 * 本测试用嵌入式 PostgreSQL（zonky）直接跑 SQL，不启动 Spring 上下文，跑得快，
 * 断言精确到列宽和插入行为。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongUrlColumnWidthTest {

    private static EmbeddedPostgres pg;
    private static Connection conn;

    /**
     * 一段模拟 sub2api 真实回跳地址的长 URL：域名 + order 参数 + resume_token(JWT) + status。
     * 400+ 字符，明确超过 255。
     */
    private static final String LONG_RETURN_URL =
            "https://token.copliot.cloud/payment/result?order_id=7&out_trade_no=sub2_MTM209VerifyLongUrl"
                    + "&resume_token=eyJvaWQiOjcsInVpZCI6MSwicGkiOiIxIiwicGsiOiJlYXN5cGF5IiwicHQiOiJ3eHBheSI"
                    + "sInJ1IjoiaHR0cHM6Ly90b2tlbi5jb3BsaW90LmNsb3VkL3BheW1lbnQvcmVzdWx0IiwiaWF0IjoxNzg3MDc"
                    + "wNjY0LCJleHAiOjE3ODcxNTcwNjR9.n1hobviRhYU4VfAJ1_vf6kMTjTHFOCI0jNie9tykfpw_extra_pad"
                    + "ding_to_push_over_two_hundred_fifty_five_chars_for_this_regression&status=success";

    @BeforeAll
    static void startDatabase() throws Exception {
        assertThat(LONG_RETURN_URL.length())
                .as("测试用长 URL 必须明确大于 255 字符，否则起不到守护作用")
                .isGreaterThan(255);

        pg = EmbeddedPostgres.builder().start();
        conn = pg.getPostgresDatabase().getConnection();
        // 建表用仓库里的 init-pg.sql，和生产 / 全流程测试完全同一份。
        ScriptUtils.executeSqlScript(conn,
                new EncodedResource(new ClassPathResource("db/init-pg.sql"), StandardCharsets.UTF_8));
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
        if (pg != null) {
            pg.close();
        }
    }

    /**
     * 情况一：init-pg.sql 建出来的新库，四个 URL 列本来就是 VARCHAR(2000)，插入 >255 的
     * URL 应当直接成功。防止将来有人误把 init-pg.sql 里的列宽改回 255。
     */
    @Test
    @Order(1)
    void freshInitSchema_acceptsUrlLongerThan255() throws Exception {
        assertColumnWidth("fp_pay_order", "notify_url", 2000);
        assertColumnWidth("fp_pay_order", "return_url", 2000);
        assertColumnWidth("fp_merchant", "notify_url", 2000);
        assertColumnWidth("fp_merchant", "return_url", 2000);

        // 直接往订单表塞一行长 return_url，走 JDBC，绕过 Java 侧任何截断逻辑。
        String orderNo = "FP_MTM209_FRESH_" + System.nanoTime();
        insertOrderWithReturnUrl(orderNo, LONG_RETURN_URL);
        assertThat(readReturnUrl(orderNo)).isEqualTo(LONG_RETURN_URL);
    }

    /**
     * 情况二：模拟"生产库还没跑 V1_5"的老库 —— 把四个列缩回 255，此时插入长 URL 会报错；
     * 跑一次 V1_5 迁移脚本后再插入应当成功；再跑一次 V1_5（幂等验证）不能报错。
     */
    @Test
    @Order(2)
    void v1_5Migration_widensLegacyColumns_isIdempotent() throws Exception {
        // 0. 前一个用例塞进的长 URL 行清掉，否则 ALTER 缩回 255 会因为已有超长数据被 PG 拒绝
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM fp_pay_order");
        }

        // 1. 把四个列缩回 255，模拟未加宽的老库
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE fp_pay_order ALTER COLUMN notify_url TYPE VARCHAR(255)");
            stmt.execute("ALTER TABLE fp_pay_order ALTER COLUMN return_url TYPE VARCHAR(255)");
            stmt.execute("ALTER TABLE fp_merchant  ALTER COLUMN notify_url TYPE VARCHAR(255)");
            stmt.execute("ALTER TABLE fp_merchant  ALTER COLUMN return_url TYPE VARCHAR(255)");
        }
        assertColumnWidth("fp_pay_order", "return_url", 255);

        // 2. 现在插入长 URL 必然报 value too long。用 SQLState 判定避免本地/CI 中英文错误信息差异：
        // PG 里 "string_data_right_truncation" 就是 SQLSTATE 22001
        String orderNoLegacy = "FP_MTM209_LEGACY_" + System.nanoTime();
        assertThatThrownBy(() -> insertOrderWithReturnUrl(orderNoLegacy, LONG_RETURN_URL))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState())
                                .as("期望 SQLSTATE=22001 (string_data_right_truncation)，实际=%s", e.getSQLState())
                                .isEqualTo("22001"));

        // 3. 跑一次 V1_5 迁移
        runMigrationV1_5();
        assertColumnWidth("fp_pay_order", "notify_url", 2000);
        assertColumnWidth("fp_pay_order", "return_url", 2000);
        assertColumnWidth("fp_merchant", "notify_url", 2000);
        assertColumnWidth("fp_merchant", "return_url", 2000);

        // 4. 加宽后再插入应当成功
        String orderNoWidened = "FP_MTM209_WIDE_" + System.nanoTime();
        insertOrderWithReturnUrl(orderNoWidened, LONG_RETURN_URL);
        assertThat(readReturnUrl(orderNoWidened)).isEqualTo(LONG_RETURN_URL);

        // 5. 再跑一次（幂等验证）不能报错，列宽仍是 2000
        runMigrationV1_5();
        assertColumnWidth("fp_pay_order", "return_url", 2000);
    }

    private void runMigrationV1_5() throws Exception {
        ScriptUtils.executeSqlScript(conn,
                new EncodedResource(
                        new ClassPathResource("db/migration/V1_5__widen_url_columns_pg.sql"),
                        StandardCharsets.UTF_8));
    }

    private void insertOrderWithReturnUrl(String orderNo, String returnUrl) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO fp_pay_order (order_no, out_trade_no, merchant_id, pay_type, amount, "
                        + "subject, status, return_url, notify_status, notify_count, order_source) "
                        + "VALUES (?, ?, 1, 'wxpay', 1.00, 'MTM-209 长 URL 回归', 0, ?, 0, 0, 'epay')")) {
            ps.setString(1, orderNo);
            ps.setString(2, orderNo);
            ps.setString(3, returnUrl);
            ps.executeUpdate();
        }
    }

    private String readReturnUrl(String orderNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT return_url FROM fp_pay_order WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString("return_url");
            }
        }
    }

    private void assertColumnWidth(String table, String column, int expectedLength) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s 找不到", table, column).isTrue();
                assertThat(rs.getInt("character_maximum_length"))
                        .as("%s.%s 期望列宽 %d", table, column, expectedLength)
                        .isEqualTo(expectedLength);
            }
        }
    }
}
