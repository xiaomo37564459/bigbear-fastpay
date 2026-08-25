package com.fastpay.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁死 MyBatis 日志实现在不同 profile 下的行为（MTM-239）：
 *   1) 生产环境（application-prod.yml）不许开 StdOutImpl —— 它会把每一条 SQL
 *      连同查询结果的整行数据都打到 stdout，包含商户 apiKey / apiSecret /
 *      登录密码密文等敏感字段。线上日志文件权限是「机器上谁都能读」，等于把
 *      商户密钥公开出去。
 *   2) 开发环境（application-dev.yml）保留 StdOutImpl —— 本地调试要看 SQL。
 *
 * 这两条不变量由这个测试守着，任何人想改动都会先看到红。
 */
class MybatisLogImplProfileTest {

    private static final String STDOUT_IMPL = "org.apache.ibatis.logging.stdout.StdOutImpl";

    @Test
    void prodProfileMustNotEnableStdOutLogging() {
        Map<String, Object> config = loadYaml("application-prod.yml");
        String logImpl = readLogImpl(config);
        assertThat(logImpl)
                .as("生产环境 mybatis-plus.configuration.log-impl 不能是 StdOutImpl，"
                        + "否则商户 apiKey/apiSecret/密码密文会随 SQL 结果打到日志（MTM-239）")
                .isNotEqualTo(STDOUT_IMPL);
    }

    @Test
    void devProfileKeepsStdOutLoggingForLocalDebugging() {
        Map<String, Object> config = loadYaml("application-dev.yml");
        String logImpl = readLogImpl(config);
        assertThat(logImpl)
                .as("开发环境要保留 StdOutImpl 方便本地看 SQL，不能被一起摘掉")
                .isEqualTo(STDOUT_IMPL);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String classpathResource) {
        try (InputStream in = MybatisLogImplProfileTest.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertThat(in).as("找不到配置文件: " + classpathResource).isNotNull();
            Object loaded = new Yaml().load(in);
            return loaded == null ? Collections.emptyMap() : (Map<String, Object>) loaded;
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + classpathResource + " 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String readLogImpl(Map<String, Object> root) {
        Object mybatisPlus = root.get("mybatis-plus");
        if (!(mybatisPlus instanceof Map)) {
            return null;
        }
        Object configuration = ((Map<String, Object>) mybatisPlus).get("configuration");
        if (!(configuration instanceof Map)) {
            return null;
        }
        Object value = ((Map<String, Object>) configuration).get("log-impl");
        return value == null ? null : value.toString();
    }
}
