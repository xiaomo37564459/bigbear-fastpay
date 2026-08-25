package com.fastpay.config;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接复现 MyBatis 两种日志实现（MTM-239）：
 *   1) StdOutImpl 会把每一条 `.debug(...)` 消息（也就是 SQL 和查询结果整行）
 *      不打折扣地写到 System.out —— 一旦 fp_merchant 被查一次，apiKey/apiSecret
 *      就跟着 `<== Row:` 打进日志文件
 *   2) 关掉后（NoLoggingImpl 或不设 log-impl 走 SLF4J 默认），同样内容不会出现在 stdout
 *
 * 这个测试不启 Spring、不连数据库，只对着 MyBatis 的 Log 抽象验证两种实现的输出差异。
 */
class MybatisStdOutLeakReproTest {

    private static final String SENSITIVE_ROW =
            "<==      Row: 1, M001, apikey-xxxxxxxxxxxxxxxxxxxxxxxx, apisecret-yyyyyyyyyyyyyyyyyyyyy, $2a$10$abcdefghij...";

    @Test
    void stdOutImplLeaksTheRowVerbatim() {
        String captured = withCapturedStdout(() -> {
            LogFactory.useCustomLogging(StdOutImpl.class);
            Log log = LogFactory.getLog("com.fastpay.mapper.MerchantMapper");
            log.debug(SENSITIVE_ROW);
        });
        assertThat(captured)
                .as("StdOutImpl 直接把整条 debug 消息写到 System.out —— 这就是线上密钥泄漏的路径")
                .contains("apikey-xxxxxxxxxxxxxxxxxxxxxxxx")
                .contains("apisecret-yyyyyyyyyyyyyyyyyyyyy");
    }

    @Test
    void noLoggingImplKeepsRowOutOfStdout() {
        String captured = withCapturedStdout(() -> {
            LogFactory.useCustomLogging(NoLoggingImpl.class);
            Log log = LogFactory.getLog("com.fastpay.mapper.MerchantMapper");
            log.debug(SENSITIVE_ROW);
        });
        assertThat(captured)
                .as("关掉 StdOutImpl 之后，同样的行不会再出现在 stdout")
                .doesNotContain("apikey-")
                .doesNotContain("apisecret-");
    }

    private static String withCapturedStdout(Runnable action) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream tee = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(tee);
            action.run();
        } finally {
            System.setOut(originalOut);
            // 重置回 MyBatis 默认，避免影响后续测试
            LogFactory.useSlf4jLogging();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
