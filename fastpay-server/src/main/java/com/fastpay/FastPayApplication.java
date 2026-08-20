package com.fastpay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * Fast 易支付 - 启动类
 * 个人免签支付平台，提供便捷的收款解决方案
 *
 * @author xiaomo37564459
 * @version 1.0.0
 */
@SpringBootApplication
@MapperScan("com.fastpay.mapper")
public class FastPayApplication {

    // JVM 一起来就把默认时区钉死到 Asia/Shanghai，让服务端的时间处理不再看宿主机脸色（MTM-187）。
    //
    // 为什么必须在这里做：
    //   - 订单过期时间、支付时间、创建时间等一律用 LocalDateTime，取值都是 LocalDateTime.now()，
    //     结果直接跟着 JVM 默认时区走 —— 机器在东八区就是北京时间，机器在零时区就是 UTC。
    //   - MySQL 的 JDBC URL 里写死了 serverTimezone=Asia/Shanghai。驱动写入 Timestamp 时会做
    //     「JVM 默认时区 → serverTimezone」的转换，写入 LocalDateTime（MyBatis 走 setObject）时
    //     不做转换，两条写入路径的结果差一个 JVM 与东八区的时差。机器不在东八区时，同一段代码在
    //     数据库里存出来的值就对不上，直接导致「订单超时不关单」（MySqlFullFlowTest#step7）。
    //   - PostgreSQL 那条连接没有 serverTimezone 参数，但两条数据库路径的行为应当一致；把 JVM
    //     时区钉死就是那条一致性保证。
    //
    // 放在 static 块里而不是 main() 里：为的是让 Spring Boot 测试上下文（不走 main()）也自动生效。
    // Spring Boot 会加载 @SpringBootApplication 标注的类，触发这里的 static 初始化。
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        System.setProperty("user.timezone", "Asia/Shanghai");
    }

    public static void main(String[] args) {
        SpringApplication.run(FastPayApplication.class, args);
        System.out.println("====================================");
        System.out.println("   Fast 易支付 服务启动成功！");
        // System.out.println("   管理后台: http://localhost:3001/fastpay-admin/");
        // System.out.println("   商户平台: http://localhost:3002/fastpay-merchant/");
        System.out.println("   API地址: http://localhost:7001/fastpay-server/api");
        System.out.println("====================================");
    }
}
