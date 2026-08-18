package com.fastpay.util;

import cn.hutool.crypto.SecureUtil;

import java.util.Map;
import java.util.TreeMap;

/**
 * 彩虹易支付（通用「易支付」协议）签名工具
 *
 * 规则和现有 {@link SignUtil} 不同，两者不能互换：
 * 1. 参数按 ASCII 升序排序
 * 2. 拼成 a=1&b=2 形式（不做 URL 编码）
 * 3. 过滤 sign、sign_type、空值
 * 4. 末尾直接拼商户密钥（**不是** &key=）
 * 5. MD5 后转小写
 *
 * 独立成一个类，禁止把「易支付协议入站请求」和「Fast 易支付原生 API」两条签名路径合并。
 */
public class EpaySignUtil {

    /**
     * 按易支付规则生成签名
     */
    public static String generateSign(Map<String, String> params, String apiSecret) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("sign".equals(key) || "sign_type".equals(key)) {
                continue;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            sorted.put(key, value);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }

        // 关键差异：直接拼密钥，不是 &key=
        sb.append(apiSecret);

        return SecureUtil.md5(sb.toString()).toLowerCase();
    }

    /**
     * 校验请求参数中的 sign 是否正确
     */
    public static boolean verifySign(Map<String, String> params, String apiSecret) {
        if (params == null) {
            return false;
        }
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        String expected = generateSign(params, apiSecret);
        return expected.equalsIgnoreCase(sign);
    }
}
