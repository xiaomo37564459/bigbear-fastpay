package com.fastpay;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * PostgreSQL 路径的主流程测试。
 * 用 zonky embedded-postgres 在本机临时拉起一个真实的 PostgreSQL 实例，不依赖 Docker、
 * 不依赖机器上装没装 PG，测试跑完自动销毁，不留任何数据。
 * 建表用的是新增的 db/init-pg.sql（PostgreSQL 版本）。
 */
class PostgresFullFlowTest extends AbstractFullFlowTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    @BeforeAll
    static void startDatabase() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        // zonky 默认已经建好了一个叫 postgres 的库、一个叫 postgres 的超级用户，本地连接免密，
        // 直接在这个库里建表就够了，不用再额外建库
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

    @Override
    protected String migrationCloseConflictingUnpaidSql() {
        // PG 允许 UPDATE 里 SELECT 同一张表
        return "UPDATE fp_pay_order SET status = 3 " +
                "WHERE status = 0 " +
                "  AND id NOT IN (" +
                "      SELECT MIN(id) FROM fp_pay_order " +
                "      WHERE status = 0 " +
                "      GROUP BY merchant_id, pay_type, pay_amount" +
                "  )";
    }

    @Override
    protected String migrationInsertLegacyPendingSql() {
        return "INSERT INTO fp_pending_pay_amount (merchant_id, pay_type, pay_amount, order_no, expire_time) " +
                "SELECT merchant_id, pay_type, pay_amount, order_no, expire_time " +
                "FROM fp_pay_order WHERE status = 0 " +
                "ON CONFLICT (merchant_id, pay_type, pay_amount) DO NOTHING";
    }
}
