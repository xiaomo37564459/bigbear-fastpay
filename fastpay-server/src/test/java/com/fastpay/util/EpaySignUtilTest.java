package com.fastpay.util;

import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 彩虹易支付签名工具单元测试
 *
 * 覆盖彩虹易支付通用协议的签名算法核心行为：
 * 1. 参数按 ASCII 升序排序
 * 2. 拼成 a=1&b=2 形式（不做 URL 编码）
 * 3. 末尾直接拼商户密钥（不是 &key= 形式）
 * 4. MD5 后转小写
 * 5. 参与签名的参数中要排除 sign、sign_type 和空值
 */
class EpaySignUtilTest {

    @Test
    void generateSign_isLowercaseMd5OfSortedKvPlusKey() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("b", "2");
        params.put("a", "1");
        params.put("c", "3");

        String sign = EpaySignUtil.generateSign(params, "secret");

        // 排序 + 直接拼密钥（无 &key= 前缀）+ MD5 小写
        String expected = SecureUtil.md5("a=1&b=2&c=3secret").toLowerCase();
        assertThat(sign).isEqualTo(expected);
        assertThat(sign).matches("[0-9a-f]{32}");
    }

    @Test
    void generateSign_excludesSignAndSignTypeAndEmptyValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1000");
        params.put("type", "alipay");
        params.put("out_trade_no", "IT001");
        params.put("name", "测试商品");
        params.put("money", "1.00");
        params.put("notify_url", "http://a/notify");
        params.put("return_url", "");   // 空值应被剔除
        params.put("device", null);       // null 也剔除
        params.put("sign", "SHOULD_BE_IGNORED");
        params.put("sign_type", "MD5");

        String sign = EpaySignUtil.generateSign(params, "myKey");

        String expected = SecureUtil.md5(
                "money=1.00&name=测试商品&notify_url=http://a/notify&out_trade_no=IT001&pid=1000&type=alipaymyKey"
        ).toLowerCase();
        assertThat(sign).isEqualTo(expected);
    }

    @Test
    void generateSign_differsFromLegacySignUtil() {
        // 关键回归防护：同一组参数、同一密钥，易支付算法（小写、无 &key= 前缀）
        // 和现有 SignUtil（大写、带 &key= 前缀）必须产出不同的签名，
        // 谁也不能替代谁，防止未来有人误用
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "1");
        params.put("b", "2");

        String epaySign = EpaySignUtil.generateSign(params, "secret");

        Map<String, Object> legacyParams = new HashMap<>(params);
        String legacySign = SignUtil.generateSign(legacyParams, "secret");

        assertThat(epaySign).isNotEqualTo(legacySign);
        assertThat(epaySign).isEqualTo(epaySign.toLowerCase());
        assertThat(legacySign).isEqualTo(legacySign.toUpperCase());
    }

    @Test
    void verifySign_acceptsCorrectSignRegardlessOfCase() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1000");
        params.put("out_trade_no", "IT001");
        String sign = EpaySignUtil.generateSign(params, "secret");

        Map<String, String> verifyParams = new LinkedHashMap<>(params);
        verifyParams.put("sign", sign);
        verifyParams.put("sign_type", "MD5");
        assertThat(EpaySignUtil.verifySign(verifyParams, "secret")).isTrue();

        // 大小写不敏感（个别对接方会传大写签名过来）
        Map<String, String> upperCase = new LinkedHashMap<>(params);
        upperCase.put("sign", sign.toUpperCase());
        upperCase.put("sign_type", "MD5");
        assertThat(EpaySignUtil.verifySign(upperCase, "secret")).isTrue();
    }

    @Test
    void verifySign_rejectsBadSign() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1000");
        params.put("out_trade_no", "IT001");
        params.put("sign", "wrong_sign_value");
        params.put("sign_type", "MD5");

        assertThat(EpaySignUtil.verifySign(params, "secret")).isFalse();
    }

    @Test
    void verifySign_rejectsMissingOrEmptySign() {
        Map<String, String> withoutSign = new LinkedHashMap<>();
        withoutSign.put("pid", "1000");
        assertThat(EpaySignUtil.verifySign(withoutSign, "secret")).isFalse();

        Map<String, String> emptySign = new LinkedHashMap<>();
        emptySign.put("pid", "1000");
        emptySign.put("sign", "");
        assertThat(EpaySignUtil.verifySign(emptySign, "secret")).isFalse();
    }

    @Test
    void generateSign_ignoresValuesButKeepsKeysInSort() {
        // 空 value 参与不了签名，但是不影响其它键的排序
        Map<String, String> a = new LinkedHashMap<>();
        a.put("x", "1");
        a.put("y", "2");
        a.put("empty", "");

        Map<String, String> b = new LinkedHashMap<>();
        b.put("x", "1");
        b.put("y", "2");
        // b 没有 empty 键

        assertThat(EpaySignUtil.generateSign(a, "k"))
                .isEqualTo(EpaySignUtil.generateSign(b, "k"));
    }
}
