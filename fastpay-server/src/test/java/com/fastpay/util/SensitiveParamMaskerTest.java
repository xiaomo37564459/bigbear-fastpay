package com.fastpay.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MTM-252：把「要落到日志里的参数 Map」里凡是名字看着像密钥/签名/密码的字段，
 * 值一律打码。这里只覆盖工具本身的行为：
 *   1. 敏感名（key/sign/secret/password/token 及常见变体）会被替换成前 4 + *** + 后 4 的样式
 *   2. 短值（<= 8 字符）不好打码，一律换成 ***
 *   3. 非敏感字段（pid、out_trade_no、money、type 这种排查用的）原样保留
 *   4. sign_type 装的是算法名（"MD5"），不是密钥，不该被打码 —— 特意允许
 *   5. 大小写、下划线、驼峰、连字符都能命中
 */
class SensitiveParamMaskerTest {

    @Test
    void masksExactlyKeyField() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("key", "verysecretapikeyvalue1234567890");

        Map<String, String> out = SensitiveParamMasker.mask(in);

        assertThat(out.get("key"))
                .as("敏感字段：保留前 4 后 4，中间打星，能对得上是哪一条又拿不到原值")
                .isEqualTo("very***7890");
        // 原 map 不能被改掉，业务代码后面还要用
        assertThat(in.get("key")).isEqualTo("verysecretapikeyvalue1234567890");
    }

    @Test
    void masksShortValueEntirely() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("password", "abcd1234");           // 8 字符：太短，前 4 后 4 会把整段露出来

        Map<String, String> out = SensitiveParamMasker.mask(in);

        assertThat(out.get("password")).isEqualTo("***");
    }

    @Test
    void keepsNonSensitiveFieldsVisible() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("pid", "M20260825TEST");
        in.put("out_trade_no", "ORDER-0001");
        in.put("money", "12.34");
        in.put("type", "wxpay");
        in.put("name", "商品名");

        Map<String, String> out = SensitiveParamMasker.mask(in);

        assertThat(out).isEqualTo(in);
    }

    @Test
    void hitsCommonVariants() {
        Map<String, String> in = new LinkedHashMap<>();
        String sentinel = "SENTINEL-VALUE-LONG-ENOUGH-1234567890";
        in.put("KEY", sentinel);
        in.put("api_key", sentinel);
        in.put("apiKey", sentinel);
        in.put("api-key", sentinel);
        in.put("apiSecret", sentinel);
        in.put("api_secret", sentinel);
        in.put("access_token", sentinel);
        in.put("accessToken", sentinel);
        in.put("Password", sentinel);
        in.put("pwd", sentinel);
        in.put("signature", sentinel);
        in.put("sign", sentinel);

        Map<String, String> out = SensitiveParamMasker.mask(in);

        for (Map.Entry<String, String> e : out.entrySet()) {
            assertThat(e.getValue())
                    .as("字段 %s 必须被打码", e.getKey())
                    .doesNotContain(sentinel)
                    .isNotEqualTo(sentinel);
        }
    }

    @Test
    void keepsSignTypeVisibleBecauseItsAlgoNameNotSecret() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("sign_type", "MD5");
        in.put("signType", "MD5");

        Map<String, String> out = SensitiveParamMasker.mask(in);

        assertThat(out.get("sign_type"))
                .as("sign_type 装的是算法名 MD5，不是密钥；打码会浪费排查线索")
                .isEqualTo("MD5");
        assertThat(out.get("signType")).isEqualTo("MD5");
    }

    @Test
    void nullValueStaysNull() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("key", null);

        Map<String, String> out = SensitiveParamMasker.mask(in);

        assertThat(out).containsEntry("key", null);
    }

    @Test
    void nullOrEmptyMapReturnsSameReference() {
        assertThat(SensitiveParamMasker.mask(null)).isNull();
        Map<String, String> empty = new LinkedHashMap<>();
        assertThat(SensitiveParamMasker.mask(empty)).isSameAs(empty);
    }
}
