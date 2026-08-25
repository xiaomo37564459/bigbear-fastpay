package com.fastpay.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MTM-162 复检发现原来的 IP 取值能被绕过，改成信任模型驱动的 {@link ClientIpResolver}。
 * 这些测试锁死那三条来源的优先级、边界字符校验、和长度上限。
 */
class ClientIpResolverTest {

    @Test
    void prefersXRealIp_evenWhenXForwardedForPresent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "203.0.113.50");
        req.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.50");
        req.setRemoteAddr("192.0.2.1");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.50");
    }

    @Test
    void spoofedXForwardedFor_takesLastEntry_notFirst() {
        // 攻击者在 XFF 里塞一个假地址，nginx 用 $proxy_add_x_forwarded_for 把真实 IP 拼在后面。
        // 老实现把整串直接当钥匙用，攻击者每次换头就换钥匙，永远锁不上；这里锁死我们只取最后一段。
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.50");
        req.setRemoteAddr("192.0.2.1");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.50");
    }

    @Test
    void multipleSpoofedEntries_stillLandsOnLastRealOne() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3, 203.0.113.50");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.50");
    }

    @Test
    void singleEntryXForwardedFor_worksTransparently() {
        // 本地开发或直连场景：单条 XFF 直接作为最后一段
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.42");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.42");
    }

    @Test
    void ipv6RealIp_isAccepted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "2001:db8::1");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("2001:db8::1");
    }

    @Test
    void overlongHeader_isRejected_fallsBackToRemoteAddr() {
        // 428 字符的头 —— QA 复检时的实际打法。老实现直接把它写进 identity_key 触发 500。
        String garbage = "1.1.1.1".repeat(70);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", garbage);
        req.addHeader("X-Forwarded-For", garbage);
        req.setRemoteAddr("192.0.2.1");

        String resolved = ClientIpResolver.resolve(req);
        assertThat(resolved).isEqualTo("192.0.2.1");
        assertThat(resolved.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void garbageChars_areRejected_fallsBackToRemoteAddr() {
        // 用 <script> / SQL 注入 / 换行等垃圾字符：一律拒收
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "<script>alert(1)</script>");
        req.addHeader("X-Forwarded-For", "9.9.9.9', OR '1'='1");
        req.setRemoteAddr("192.0.2.1");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("192.0.2.1");
    }

    @Test
    void allSourcesMissing_returnsUnknown_notNull() {
        // 三条来源全没：至少返回一个非 null 的字符串，让登录限次的账号维度还能正常工作
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(null);

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("unknown");
    }

    @Test
    void emptyXRealIp_fallsThroughToXForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "   ");
        req.addHeader("X-Forwarded-For", "203.0.113.50");

        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.50");
    }

    @Test
    void nullRequest_returnsUnknown_doesNotThrow() {
        assertThat(ClientIpResolver.resolve(null)).isEqualTo("unknown");
    }
}
