package com.fastpay.util;

import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码存储工具单元测试。
 *
 * 覆盖三件事：
 * 1. 新格式（bcrypt）：能存、能验、每次加盐都不一样
 * 2. 老格式（无盐 MD5）：库里已有的老密码能继续验过
 * 3. 判别：能一眼看出某条密码是不是「老格式」，让登录成功后好把它顺手升级
 *
 * 这些约束是 MTM-161「密码存法迁移」的核心不变式，改动 PasswordHasher 前先看这里能不能过。
 */
class PasswordHasherTest {

    private static final String RAW = "Correct-Horse-Battery-Staple-9";

    @Test
    void hash_producesBcryptFormatWithinColumnBudget() {
        String hashed = PasswordHasher.hash(RAW);

        // bcrypt 输出固定 60 字符、以 $2 开头（$2a / $2b / $2y 都算），
        // 之所以关心长度：迁移前字段是 VARCHAR(64)，60 字符正好够存，别塞不下
        assertThat(hashed).hasSize(60);
        assertThat(hashed).startsWith("$2");
    }

    @Test
    void hash_isSaltedSoTwoCallsDiffer() {
        // 同一个原文两次哈希必须不同，这就是「加盐」的核心可观察行为
        String a = PasswordHasher.hash(RAW);
        String b = PasswordHasher.hash(RAW);
        assertThat(a).isNotEqualTo(b);

        // 但两条都能验回原文
        assertThat(PasswordHasher.matches(RAW, a)).isTrue();
        assertThat(PasswordHasher.matches(RAW, b)).isTrue();
    }

    @Test
    void matches_acceptsCorrectRawAndRejectsWrong() {
        String hashed = PasswordHasher.hash(RAW);

        assertThat(PasswordHasher.matches(RAW, hashed)).isTrue();
        assertThat(PasswordHasher.matches("wrong-password", hashed)).isFalse();
    }

    @Test
    void matches_acceptsLegacyMd5HashSoOldAccountsStillLogin() {
        // 库里遗留的老账号密码字段是无盐 MD5 十六进制串
        String legacyMd5 = SecureUtil.md5(RAW);

        assertThat(legacyMd5).hasSize(32);
        assertThat(PasswordHasher.matches(RAW, legacyMd5)).isTrue();
        assertThat(PasswordHasher.matches("wrong-password", legacyMd5)).isFalse();
    }

    @Test
    void matches_isCaseInsensitiveForLegacyMd5() {
        // 老代码有的地方 md5 结果转过大写、有的地方保留小写，一起兼容
        String legacyLower = SecureUtil.md5(RAW);
        String legacyUpper = legacyLower.toUpperCase();

        assertThat(PasswordHasher.matches(RAW, legacyLower)).isTrue();
        assertThat(PasswordHasher.matches(RAW, legacyUpper)).isTrue();
    }

    @Test
    void matches_returnsFalseForNullOrBlankInputs() {
        // 边界：不能因为字段为空就把验证放过去，也不能抛异常把登录接口打崩
        assertThat(PasswordHasher.matches(null, PasswordHasher.hash(RAW))).isFalse();
        assertThat(PasswordHasher.matches(RAW, null)).isFalse();
        assertThat(PasswordHasher.matches(RAW, "")).isFalse();
    }

    @Test
    void matches_returnsFalseForMalformedBcryptWithoutThrowing() {
        // 库里被人手改过的脏数据（比如残缺的 bcrypt 串）不能让服务 500
        assertThat(PasswordHasher.matches(RAW, "$2a$10$broken")).isFalse();
    }

    @Test
    void isLegacy_distinguishesMd5FromBcrypt() {
        String bcrypt = PasswordHasher.hash(RAW);
        String md5 = SecureUtil.md5(RAW);

        assertThat(PasswordHasher.isLegacy(md5)).isTrue();
        assertThat(PasswordHasher.isLegacy(md5.toUpperCase())).isTrue();
        assertThat(PasswordHasher.isLegacy(bcrypt)).isFalse();

        // 空值不算「老格式」——避免把「压根没有密码」错当成需要升级
        assertThat(PasswordHasher.isLegacy(null)).isFalse();
        assertThat(PasswordHasher.isLegacy("")).isFalse();
    }
}
