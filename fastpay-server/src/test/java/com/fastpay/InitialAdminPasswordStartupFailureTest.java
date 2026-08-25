package com.fastpay;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MTM-246 复检修复：初始密码文件写不出去时，Spring 必须启动失败。
 *
 * 第一次交付时我在 AdminServiceImpl 里抛 BusinessException，但 InitConfig.run() 里那段
 * try/catch (Exception e) 把它吞成一条 WARN，结果服务照样起来、库里一个管理员都没有、
 * 谁都登不进去，日志把原因错报成「可能数据库未就绪」（QA 顾检验收时实测发现）。
 *
 * 这条测试从真实启动入口 SpringApplication.run() 走一遍，把 initial-password-file 指到
 * 一个注定写不出去的路径（父路径是一个"文件"而不是"目录"），确认：
 *   1. Spring 启动直接失败（run() 抛异常），而不是无声无息地起来一个没管理员的服务
 *   2. 失败原因的消息里明确出现「初始管理员账号未能创建」和密码文件路径，
 *      不会再把运维带偏到"数据库未就绪"上
 */
class InitialAdminPasswordStartupFailureTest {

    // 用 systemProperty 而不是 SpringApplication.setDefaultProperties 是有意为之：
    // application-dev.yml 里的 ${DB_URL:jdbc:mysql://...} 是「用 yml 里给的默认值」，
    // 优先级高于 SpringApplication 的 default properties；只有 System 属性能真正覆盖它。
    // 这些 key 一定要在 finally 里清掉，别污染别的用例。
    private static final String[] SYSTEM_KEYS = {
            "spring.profiles.active",
            "spring.datasource.url",
            "spring.datasource.driver-class-name",
            "spring.datasource.username",
            "spring.datasource.password",
            "fastpay.admin.password",
            "fastpay.admin.initial-password-file",
            "server.port"
    };

    @Test
    void springStartupFails_whenInitialPasswordFileCannotBeWritten() throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        Path tmpRoot = Files.createTempDirectory("fastpay-startup-fail-");
        Map<String, String> savedSysProps = new LinkedHashMap<>();
        for (String k : SYSTEM_KEYS) {
            savedSysProps.put(k, System.getProperty(k));
        }
        try {
            String jdbcUrl = pg.getJdbcUrl("postgres", "postgres");
            try (Connection conn = pg.getPostgresDatabase().getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new EncodedResource(new ClassPathResource("db/init-pg.sql"), StandardCharsets.UTF_8));
            }

            // 构造一个「写不出去」的目标路径：先在 tmpRoot 下建一个"文件" blocker.txt，
            // 然后把密码文件路径指到 blocker.txt/sub/initial-admin-password.txt。
            // Files.createDirectories(blocker.txt/sub) 会失败（blocker.txt 是文件不是目录），
            // 从而触发 writeInitialPasswordFile 的 IOException 分支。
            Path blocker = tmpRoot.resolve("blocker.txt");
            Files.writeString(blocker, "not-a-directory", StandardCharsets.UTF_8);
            Path unwritable = blocker.resolve("sub").resolve("initial-admin-password.txt");

            System.setProperty("spring.profiles.active", "dev");
            System.setProperty("spring.datasource.url", jdbcUrl);
            System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
            System.setProperty("spring.datasource.username", "postgres");
            System.setProperty("spring.datasource.password", "postgres");
            // 清空 dev 默认口令，让 initDefaultAdmin() 走「随机生成 -> 写文件」这条会失败的分支
            System.setProperty("fastpay.admin.password", "");
            System.setProperty("fastpay.admin.initial-password-file", unwritable.toString());
            // 随机端口，避免和别的测试撞
            System.setProperty("server.port", "0");

            SpringApplication app = new SpringApplication(FastPayApplication.class);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            app.setLogStartupInfo(false);

            assertThatThrownBy(() -> {
                try (ConfigurableApplicationContext ctx = app.run()) {
                    // 走到这里就说明启动没失败 —— 让 assertThatThrownBy 报「no exception thrown」
                    ctx.getBean("fastpay-server");
                }
            })
                    .as("初始密码文件写不出去时，Spring 启动必须失败，不能起来一个没管理员的空壳")
                    .hasStackTraceContaining("初始管理员账号未能创建")
                    .hasStackTraceContaining("initial-admin-password");

            // 双保险：确认 blocker 还只是一个文件，也就是 InitConfig 真的没绕过去
            assertThat(Files.isRegularFile(blocker))
                    .as("blocker 应该仍然是一个普通文件，否则说明 writeInitialPasswordFile 触发的路径不对")
                    .isTrue();
        } finally {
            pg.close();
            for (Map.Entry<String, String> entry : savedSysProps.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
            deleteRecursively(tmpRoot);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
