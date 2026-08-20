package com.fastpay.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;

/**
 * 密码存储 / 校验工具。
 *
 * 系统之前的存法是无盐 MD5，一旦数据库泄露等于所有账号密码明文外泄。这里换成 bcrypt（自带盐、自带
 * 计算成本），并让「老密码」和「新密码」在过渡期都能验过：
 *
 * - {@link #hash(String)} —— 新建 / 改密码 / 登录成功后自动升级，一律走 bcrypt
 * - {@link #matches(String, String)} —— 校验时先看库里是不是 bcrypt 格式，是就走 bcrypt；不是就退回
 *   老的无盐 MD5 比对（大小写不敏感，兼容历史数据）
 * - {@link #isLegacy(String)} —— 让调用方判断「这条记录是不是需要在下次登录成功时顺手升级成新格式」
 *
 * 判别规则：bcrypt 输出固定 60 字符、以 {@code $2} 开头（{@code $2a} / {@code $2b} / {@code $2y}
 * 都算），MD5 是 32 个十六进制字符，两者不会混淆。**故意不使用 Spring Security 的
 * {@code {bcrypt}} 前缀写法**——那种写法总长会到 68 字符，会撑破迁移前 {@code VARCHAR(64)} 的字段。
 */
public final class PasswordHasher {

    private PasswordHasher() {
    }

    /**
     * 用 bcrypt 存密码。每次调用产生的盐都不同，所以同一个原文两次 {@link #hash(String)} 结果不一样。
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /**
     * 校验原文密码是否匹配库里存的密文，兼容新旧两种格式。
     *
     * <p>空输入统一返回 {@code false}，永不抛异常——即便库里存的是脏数据（例如残缺的 bcrypt 串），
     * 也只应表现为「密码错」，不能把接口打崩。
     */
    public static boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        if (isBcrypt(storedHash)) {
            try {
                return BCrypt.checkpw(rawPassword, storedHash);
            } catch (IllegalArgumentException e) {
                // 库里的 bcrypt 串被人手改坏了：当成密码不对处理，不要把 500 抛给用户
                return false;
            }
        }
        // 老格式：无盐 MD5 十六进制串，历史数据里大小写都出现过，做大小写不敏感对比
        return SecureUtil.md5(rawPassword).equalsIgnoreCase(storedHash);
    }

    /**
     * 库里这条密码是不是老格式（需要在下一次登录成功时升级）。
     *
     * <p>空值不算老格式——避免把「压根没有密码」的异常状态误当成需要升级。
     */
    public static boolean isLegacy(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        return !isBcrypt(storedHash);
    }

    private static boolean isBcrypt(String storedHash) {
        return storedHash.length() >= 4 && storedHash.startsWith("$2");
    }
}
