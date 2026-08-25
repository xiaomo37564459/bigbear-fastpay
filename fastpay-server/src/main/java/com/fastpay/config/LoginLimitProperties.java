package com.fastpay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 登录限次的可配置阈值（MTM-162）。
 * <p>
 * 默认值：账号维度 5 次/15 分钟，IP 维度 10 次/15 分钟，锁定 15 分钟。
 * 三条阈值的取法：既能拦住脚本狂试，也让正常用户输错一两次没感觉；
 * 公司出口共用一个 IP 时账号维度先命中，避免整条 IP 被误锁全公司都进不去。
 * <p>
 * 生产环境覆盖：环境变量 FASTPAY_LOGIN_LIMIT_USER_MAX_ATTEMPTS 等，或 yml 里覆盖。
 *
 * @author xiaomo37564459
 */
@Data
@Component
@ConfigurationProperties(prefix = "fastpay.login-limit")
public class LoginLimitProperties {

    /** 同一账号在窗口内允许连续失败几次（含），超过就锁。 */
    private int userMaxAttempts = 5;

    /** 同一 IP 在窗口内允许连续失败几次（含），超过就锁。IP 维度阈值比账号维度松，避免共用出口 IP 全公司误伤。 */
    private int ipMaxAttempts = 10;

    /** 达到阈值后锁多久（分钟）。 */
    private int lockMinutes = 15;

    /** 滑动窗口：距离首次失败超过这个分钟数就把当前计数重置为 1，从头再算。 */
    private int windowMinutes = 15;

    /**
     * IP 白名单：命中的 IP 不做 IP 维度限次（账号维度照常）。
     * 用于公司出口 IP / 运维跳板机之类共用出口的场景。
     * yml 里写逗号分隔字符串或 YAML 列表都可以。
     */
    private List<String> ipWhitelist = Collections.emptyList();
}
