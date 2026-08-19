package com.fastpay.util;

import com.fastpay.common.BusinessException;

import java.util.Set;

/**
 * 管理员密码强度规则。
 * 强度不高但足够挡住"123456 / admin / password"这类人尽皆知的口令。
 * 规则很短是故意的：写在这里就是要让前端提示和后端拦截口径完全一致。
 *
 * @author xiaomo37564459
 */
public final class PasswordPolicy {

    /** 最短长度 */
    public static final int MIN_LENGTH = 8;

    /** 最长长度（Admin.password 是 VARCHAR(64) 存 MD5，明文再长也没意义，这里限制到 64） */
    public static final int MAX_LENGTH = 64;

    /**
     * 常见弱口令黑名单。命中即拒绝，即使长度和字符类型都够。
     * 只列高频泄露的那几个，不做穷举——真要泄露密码库比对由运维层面做。
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890",
            "password", "password1", "password123",
            "admin123", "admin1234", "administrator",
            "qwerty123", "qwertyuiop",
            "abc12345", "abcd1234",
            "11111111", "00000000",
            "iloveyou1"
    );

    /**
     * 给前端/接口文档统一展示的规则描述。改规则时也要改这一句。
     */
    public static final String DESCRIPTION =
            "密码长度 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位，必须同时包含字母和数字，且不能是 123456、admin123 这类常见弱口令";

    private PasswordPolicy() {
    }

    /**
     * 校验新密码是否满足强度要求。不通过直接抛业务异常。
     *
     * @param password 明文密码
     */
    public static void validate(String password) {
        if (password == null || password.isEmpty()) {
            throw BusinessException.badRequest("密码不能为空");
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw BusinessException.badRequest("密码长度必须在 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位之间");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw BusinessException.badRequest("密码必须同时包含字母和数字");
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase())) {
            throw BusinessException.badRequest("密码太常见了，请换一个不容易被猜到的");
        }
    }
}
