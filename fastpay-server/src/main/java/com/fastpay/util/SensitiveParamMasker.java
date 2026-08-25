package com.fastpay.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 参数打码工具。给「要落到日志里的参数 Map」用的：凡是名字看着像密钥、签名、密码、
 * 口令、访问令牌的字段，值都被替换成 "前 4 位 *** 后 4 位" 的样式；非敏感字段
 * （商户号、订单号、金额、通道之类排查要用的）原样保留。
 *
 * <p>触发词：{@code key} / {@code sign} / {@code signature} / {@code secret} /
 * {@code password} / {@code passwd} / {@code pwd} / {@code token}。
 * 匹配对大小写、下划线、连字符、驼峰都成立（{@code apiKey} / {@code api_key} /
 * {@code API-KEY} 都能命中）。
 *
 * <p>{@code sign_type} 装的是签名算法名（"MD5" 之类），不是密钥，特意保留可见 ——
 * 打码它只会浪费排查线索，帮不上安全。
 *
 * <p>方法返回**新的 Map**，不改动入参：业务代码后面还要拿原始参数去验签、下单，
 * 打码只服务于日志。
 *
 * <p>本工具只服务于「日志」这一个用途，不要拿去做接口出参脱敏、DB 写入脱敏 —— 那些
 * 场景对字段范围、遮挡策略的要求不一样，套过去会漏。
 */
public final class SensitiveParamMasker {

    /** 命中这些词的字段名一律打码 */
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "key", "sign", "signature", "secret", "password", "passwd", "pwd", "token"
    );

    /** 名字里带敏感词但装的其实是算法/类型标记，值本身不敏感，保留可见方便排查 */
    private static final Set<String> ALLOWLIST_LOWER = Set.of(
            "sign_type", "signtype"
    );

    private SensitiveParamMasker() {
    }

    /**
     * 返回一份打码后的新 Map。入参本身不被改动。
     * 传 null 或空 Map 时原样返回，方便调用方少一层判空。
     */
    public static Map<String, String> mask(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }
        Map<String, String> masked = new LinkedHashMap<>(params.size());
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            masked.put(name, isSensitive(name) ? maskValue(value) : value);
        }
        return masked;
    }

    static boolean isSensitive(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (ALLOWLIST_LOWER.contains(lower)) {
            return false;
        }
        // 拆开常见分隔符：api_key / api-key → ["api", "key"]；驼峰写法（apiKey）没有分隔符，
        // 只有一段 "apikey"，靠下面 endsWith 兜住
        for (String part : lower.split("[_\\-]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (SENSITIVE_WORDS.contains(part)) {
                return true;
            }
            for (String needle : SENSITIVE_WORDS) {
                if (part.length() > needle.length() && part.endsWith(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 值本身很短时（≤ 8 字符）保留前 4 后 4 会把整段露出来，一律换成 ***；
     * 长值保留前 4 后 4 中间打星，方便排查时对得上是哪一条，又拿不到能直接用的完整值。
     */
    private static String maskValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
