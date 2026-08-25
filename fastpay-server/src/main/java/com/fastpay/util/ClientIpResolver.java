package com.fastpay.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 判断「这个请求是从哪台机器来的」。MTM-162 复检时发现原来的做法能被绕过 ——
 * 直接把请求里 <code>X-Forwarded-For</code> 头的整串拿来当钥匙，但那个头是客户端自己填的，
 * 攻击者每次换一个假地址，登录限次的 IP 那道闸门就永远攒不满。这里改成按信任模型取值：
 * <ol>
 *   <li>优先看 <code>X-Real-IP</code> —— 生产 nginx 用 <code>proxy_set_header X-Real-IP $remote_addr</code>
 *       写死为它自己看到的客户端 IP，客户端填什么都会被 nginx 覆盖，攻击者改不了。</li>
 *   <li>其次看 <code>X-Forwarded-For</code> 的 <b>最后一段</b> —— nginx 用的是 <code>$proxy_add_x_forwarded_for</code>，
 *       它会把 <code>$remote_addr</code> 拼在客户端已有链条之后，所以最后一段永远是 nginx 观察到的客户端 IP，
 *       前面几段都是不可信的客户端填入值。取整串或取第一段都会被绕过；只取最后一段才是安全的。</li>
 *   <li>都没有就用 <code>request.getRemoteAddr()</code>（本地开发或跳过反代时的兜底）。</li>
 * </ol>
 * 无论走哪条分支，最终都做一次格式和长度校验：只放行看起来像 IPv4/IPv6 的字符串，
 * 超过 64 字符或含逗号 / 空格 / 中文一律拒收，回落到 <code>getRemoteAddr()</code>。
 * 这样即使有人把头怼到 400 字符去撑爆 <code>identity_key</code> 那一列，登录接口也不会崩。
 *
 * @author xiaomo37564459
 */
public final class ClientIpResolver {

    /**
     * IP 字符串的合法字符：IPv4 用 0-9 和 .；IPv6 用 0-9、a-f/A-F、:；IPv6 zone id 允许 %。
     * 只允许这些字符能顶掉整段 XFF 直接冒充（含逗号/空格的都拒），同时把超长垃圾挡在外面。
     */
    private static final int MAX_IP_LEN = 64;

    private ClientIpResolver() {
    }

    /**
     * 判断请求真实来源。返回值一定是校验过的 IP 字符串（或最坏情况下的 "unknown"），
     * 长度不超过 {@value #MAX_IP_LEN}，可以直接当作限次表的一部分 identity_key 使用。
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        String realIp = trimOrNull(request.getHeader("X-Real-IP"));
        if (isValidIp(realIp)) {
            return realIp;
        }

        String forwarded = trimOrNull(request.getHeader("X-Forwarded-For"));
        if (forwarded != null && !forwarded.isEmpty()) {
            // 逗号分隔：nginx 的 $proxy_add_x_forwarded_for 把真实 IP 拼在最后
            int lastComma = forwarded.lastIndexOf(',');
            String lastEntry = lastComma < 0 ? forwarded : forwarded.substring(lastComma + 1).trim();
            if (isValidIp(lastEntry)) {
                return lastEntry;
            }
        }

        String remote = trimOrNull(request.getRemoteAddr());
        if (isValidIp(remote)) {
            return remote;
        }
        // 拿不到就打 unknown —— 让登录限次至少按账号维度继续工作，不因取 IP 失败把登录整条挂掉
        return "unknown";
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 简单校验：只允许 IPv4 / IPv6 字符（含 zone id 分隔符 %）。挡掉逗号 / 空格 / 中文 / 引号 /
     * 换行等注入型内容，同时限制长度以防止「一头怼进 400 字符」的攻击。
     */
    private static boolean isValidIp(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_IP_LEN) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean hex = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            boolean structural = c == '.' || c == ':' || c == '%';
            if (!(digit || hex || structural)) {
                return false;
            }
        }
        return true;
    }
}
