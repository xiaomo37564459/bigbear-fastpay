package com.fastpay;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fastpay.service.AdminService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MTM-246：首次部署自动生成的管理员密码不能出现在日志里。
 *
 * 覆盖场景：initDefaultAdmin() 在数据库里没有任何管理员时走「随机生成密码」这条分支。
 * 修复前它把密码原样 warn 到日志里，任何能读日志文件的人都能拿到；
 * 修复后：
 *   1. 密码写进一个 0600 的文件（只有服务账号能读）
 *   2. 日志里只留出口路径，不再出现密码明文
 *
 * 实现说明：Spring Boot 起来时 LoggingSystem 会重建 logback，如果在 @BeforeAll 里挂 appender，
 * 那次挂载会被后面的 logging init 冲掉。所以本例的做法是：
 *   · 让 Spring 正常启动（InitConfig 建一次管理员）
 *   · 清空 fp_admin 表
 *   · 挂 appender，然后手动调用 adminService.initDefaultAdmin() 重跑一次
 *   · 从密码文件读回本次生成的密码，扫 appender 里所有事件确认没有出现
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class InitialAdminPasswordNotInLogTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;
    private static Path passwordFilePath;
    private static Path tmpDir;

    @Autowired
    private AdminService adminService;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        jdbcUrl = pg.getJdbcUrl("postgres", "postgres");
        try (Connection conn = pg.getPostgresDatabase().getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/init-pg.sql"), StandardCharsets.UTF_8));
        }
        // 密码文件放在临时目录，跑完随之清理；不能落到共享路径，避免残留
        tmpDir = Files.createTempDirectory("fastpay-init-admin-test-");
        passwordFilePath = tmpDir.resolve("initial-admin-password.txt");
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (pg != null) {
            pg.close();
        }
        if (passwordFilePath != null) {
            Files.deleteIfExists(passwordFilePath);
        }
        if (tmpDir != null) {
            Files.deleteIfExists(tmpDir);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // 明确清空 dev profile 里的默认口令，让 initDefaultAdmin() 走「随机生成」分支
        registry.add("fastpay.admin.password", () -> "");
        // 密码文件写到用例专属的临时路径
        registry.add("fastpay.admin.initial-password-file", () -> passwordFilePath.toString());
    }

    @Test
    void generatedPassword_isWrittenToRestrictedFile_notLoggedInPlaintext() throws Exception {
        // Spring 启动阶段 InitConfig 已经建过一次管理员；先把库和文件都清干净，
        // 再挂 appender、重跑一次 —— 这样能确保 appender 捕获到本次生成的所有日志事件。
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM fp_admin");
        }
        Files.deleteIfExists(passwordFilePath);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger adminLogger = (Logger) LoggerFactory.getLogger("com.fastpay.service.impl.AdminServiceImpl");
        adminLogger.setLevel(Level.ALL);
        adminLogger.addAppender(appender);
        try {
            adminService.initDefaultAdmin();
        } finally {
            adminLogger.detachAppender(appender);
            appender.stop();
        }

        // 1. 文件必须落地，且里面能读回 password= 行
        assertThat(Files.exists(passwordFilePath))
                .as("初始密码文件应该被写出来：%s", passwordFilePath)
                .isTrue();

        String fileContent = Files.readString(passwordFilePath, StandardCharsets.UTF_8);
        String password = extractPassword(fileContent);
        assertThat(password)
                .as("初始密码文件里应当有一行 password=...")
                .isNotBlank();
        assertThat(password.length())
                .as("生成的初始密码长度应当至少 8 位（当前实现是 16 位）")
                .isGreaterThanOrEqualTo(8);

        // 2. Linux / macOS 上，密码文件权限必须收到只有属主可读写
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(passwordFilePath);
            assertThat(perms)
                    .as("初始密码文件必须是 rw------- (0600)")
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException windowsHasNoPosix) {
            // Windows 没有 POSIX 权限，跳过该断言。生产环境是 Linux，运维验收时用 `ls -l` 现场复核
        }

        // 3. 关键断言：任何一条日志都不允许出现这个密码明文，一次都不行
        List<ILoggingEvent> events = appender.list;
        assertThat(events)
                .as("initDefaultAdmin() 走生成分支时至少应该 warn 出「初始密码已写入受限文件」几行")
                .isNotEmpty();
        for (ILoggingEvent event : events) {
            String formatted = event.getFormattedMessage();
            String raw = event.getMessage();
            assertThat(formatted)
                    .as("日志里禁止出现初始密码明文（formatted）：%s", formatted)
                    .doesNotContain(password);
            assertThat(raw)
                    .as("日志模板里也不能塞进密码明文（raw）：%s", raw)
                    .doesNotContain(password);
            if (event.getArgumentArray() != null) {
                for (Object arg : event.getArgumentArray()) {
                    if (arg == null) {
                        continue;
                    }
                    assertThat(arg.toString())
                            .as("日志参数里也不能包含密码明文：%s", arg)
                            .doesNotContain(password);
                }
            }
        }

        // 顺便把「模拟首次部署」的证据落到 target/ 目录下的文件，作为 MTM-246 验收附件的底稿。
        // 内容用 UTF-8 写，跨平台清晰可读；密码全部打码，只保留首尾各 2 位。
        Path evidence = Path.of("target", "mtm246-evidence.txt");
        Files.createDirectories(evidence.getParent());
        String masked = maskPassword(password);
        StringBuilder sb = new StringBuilder();
        sb.append("=== MTM-246 首次部署行为快照（自动化测试跑出来的） ===\n");
        sb.append("时间：由测试执行时间决定；密码明文已在下文中打码为 ").append(masked).append("\n");
        sb.append("\n--- 密码文件在磁盘上的位置 ---\n");
        sb.append(passwordFilePath).append("\n");
        sb.append("\n--- 密码文件的内容（password= 一行已打码）---\n");
        for (String line : fileContent.split("\\R")) {
            if (line.trim().startsWith("password=")) {
                sb.append("password=").append(masked).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        sb.append("\n--- initDefaultAdmin() 走生成分支时打出的所有 AdminServiceImpl 日志 ---\n");
        for (ILoggingEvent event : events) {
            sb.append('[').append(event.getLevel()).append("] ")
              .append(event.getFormattedMessage().replace(password, masked))
              .append('\n');
        }
        sb.append("\n--- 断言 ---\n");
        sb.append("· 密码文件存在：TRUE\n");
        sb.append("· 密码文件权限 rw-------（Windows 跳过该断言）：已通过\n");
        sb.append("· 生成的密码明文在 ").append(events.size())
          .append(" 条日志事件里出现次数：0\n");
        Files.writeString(evidence, sb.toString(), StandardCharsets.UTF_8);
    }

    private String maskPassword(String password) {
        if (password.length() <= 4) {
            return "****";
        }
        return password.substring(0, 2) + "*".repeat(password.length() - 4)
                + password.substring(password.length() - 2);
    }

    private String extractPassword(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("password=")) {
                return trimmed.substring("password=".length()).trim();
            }
        }
        return null;
    }
}
