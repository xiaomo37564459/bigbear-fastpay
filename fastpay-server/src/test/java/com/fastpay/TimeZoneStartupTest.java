package com.fastpay;

import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时区启动契约（MTM-187）
 *
 * 服务端所有时间字段（订单过期、支付时间、创建时间等）都用 LocalDateTime，取值靠 LocalDateTime.now()。
 * 一旦 JVM 默认时区不是东八区，就会出现「代码算出的过期时间」和「JDBC 存进 MySQL 的时间」两边差
 * 一个时差，超时订单该关不关。为了让服务不看宿主机脸色，FastPayApplication 在 static 初始化块里把
 * JVM 默认时区钉死到 Asia/Shanghai。
 *
 * 只要加载了 FastPayApplication（无论走 SpringApplication.run 还是走 Spring Boot 测试上下文），
 * 这条断言就应当成立。别人改动了初始化顺序或时区策略，这里第一时间会红。
 */
class TimeZoneStartupTest {

    @Test
    void jvmDefaultTimeZoneIsFixedToAsiaShanghai() throws ClassNotFoundException {
        // Class.forName(name) 会强制触发目标类的 static 初始化；
        // 直接写 FastPayApplication.class 只拿到类字面量，按 JLS 不会触发 static 块
        Class.forName(FastPayApplication.class.getName());

        TimeZone actual = TimeZone.getDefault();
        assertThat(actual.getID())
                .as("FastPayApplication 应当在启动阶段把 JVM 默认时区钉死到 Asia/Shanghai，防止服务器时区一变就出错")
                .isEqualTo("Asia/Shanghai");

        assertThat(System.getProperty("user.timezone"))
                .as("user.timezone 系统属性同时被写成 Asia/Shanghai，避免其他库读旧值")
                .isEqualTo("Asia/Shanghai");
    }
}
