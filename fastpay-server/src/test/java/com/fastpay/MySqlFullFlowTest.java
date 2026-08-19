package com.fastpay;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * MySQL 路径的主流程测试。
 * 用 MariaDB4j 在本机临时拉起一个真实的 MariaDB（MySQL 协议兼容）实例，不依赖 Docker、
 * 不依赖机器上装没装 MySQL，测试跑完自动销毁，不留任何数据。
 * 建表用的是原样的 db/init.sql（MySQL 版本，一个字都没改）。
 */
class MySqlFullFlowTest extends AbstractFullFlowTest {

    private static DB db;
    private static String jdbcUrl;

    @BeforeAll
    static void startDatabase() throws Exception {
        DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(0); // 0 = 自动挑一个空闲端口，避免和别的实例撞端口
        DBConfiguration config = builder.build();

        db = DB.newEmbeddedDB(config);
        db.start();

        // 不用 db.createDB(...)（它是另外拉起一个 mysql.exe 客户端进程去建库），直接用一条不带库名的
        // 连接把原样的 init.sql 跑一遍——脚本第一句就是 CREATE DATABASE IF NOT EXISTS，建库这件事让脚本自己做，
        // 这样也更贴近真实部署时"直接跑 init.sql"的用法
        String bootstrapUrl = "jdbc:mysql://localhost:" + config.getPort()
                + "/?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(bootstrapUrl, "root", "")) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/init.sql"), StandardCharsets.UTF_8));
        }

        jdbcUrl = "jdbc:mysql://localhost:" + config.getPort()
                + "/bigbear_fastpay?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (db != null) {
            db.stop();
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
    }

    @Override
    protected String migrationCloseConflictingUnpaidSql() {
        // MySQL 不能在 UPDATE 里 SELECT 同一张表，用 "SELECT 套一层" 骗过语法限制
        return "UPDATE fp_pay_order SET status = 3 " +
                "WHERE status = 0 " +
                "  AND id NOT IN (" +
                "      SELECT keep_id FROM (" +
                "          SELECT MIN(id) AS keep_id " +
                "          FROM fp_pay_order " +
                "          WHERE status = 0 " +
                "          GROUP BY merchant_id, pay_type, pay_amount" +
                "      ) AS keepers" +
                "  )";
    }

    @Override
    protected String migrationInsertLegacyPendingSql() {
        return "INSERT INTO fp_pending_pay_amount (merchant_id, pay_type, pay_amount, order_no, expire_time) " +
                "SELECT merchant_id, pay_type, pay_amount, order_no, expire_time " +
                "FROM fp_pay_order WHERE status = 0 " +
                "ON DUPLICATE KEY UPDATE order_no = VALUES(order_no)";
    }
}
