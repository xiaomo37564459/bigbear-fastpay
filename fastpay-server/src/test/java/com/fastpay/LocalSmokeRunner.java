package com.fastpay;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * 本地手工冒烟工具：在开发机上启动一个带 embedded PostgreSQL 的完整 Spring Boot 实例，
 * 用来配合前端 vite dev server 做端到端点击验证 —— 不依赖机器上装没装 Postgres/Docker，
 * 也不污染任何真实数据库。
 *
 * 用法：
 *   mvn -pl fastpay-server test-compile
 *   mvn -pl fastpay-server exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.fastpay.LocalSmokeRunner
 *
 * 启动完成后：
 *   - 后端地址：http://localhost:7001/fastpay-server
 *   - 前端配套：VITE_API_PROXY=http://localhost:7001 npm --prefix fastpay-admin run dev
 *   - 默认账号：admin / 123456（来自 application-dev.yml；仅本地/测试使用）
 *
 * Ctrl+C 停止时会顺手把 embedded Postgres 也关掉。
 */
public class LocalSmokeRunner {

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        String jdbcUrl = pg.getJdbcUrl("postgres", "postgres");
        try (Connection conn = pg.getPostgresDatabase().getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/init-pg.sql"), StandardCharsets.UTF_8));
        }

        System.setProperty("spring.profiles.active", "dev");
        System.setProperty("spring.datasource.url", jdbcUrl);
        System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
            }
        }));

        FastPayApplication.main(args);
    }
}
