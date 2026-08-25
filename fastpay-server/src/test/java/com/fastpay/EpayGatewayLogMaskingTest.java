package com.fastpay;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fastpay.controller.pay.EpayGatewayController;
import com.fastpay.mapper.MerchantMapper;
import com.fastpay.service.PayOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MTM-252：/submit.php 和 /mapi.php 收到请求后打的那条 "params={}" 日志会把对接方
 * 传的所有参数原样落盘。只要对接方误把商户密钥当参数塞进来
 * （sub2api 系的老代码本来就有把 key= 塞成参数的习惯），密钥就跟着这行日志漏出去。
 *
 * 这里用 Logback 的 {@link ListAppender} 抓 controller 打出的日志，然后把一段独一无二的
 * 「哨兵密钥」当 key/secret/sign 参数塞进请求，断言：
 *   1. 日志里绝不能出现完整的哨兵值 —— 敏感字段必须打码
 *   2. 商户号、订单号这种排查要用的字段还得能看见 —— 不能一刀把日志砍死
 *   3. /api.php 目前没有 params={} 日志，测试也把它守起来：以后有人补日志时不能忘了打码
 *
 * 写法参考 MTM-239 的 MybatisStdOutLeakReproTest：直接对日志层做复现，不依赖真实数据库。
 */
@ExtendWith(MockitoExtension.class)
class EpayGatewayLogMaskingTest {

    /** 独一无二的记号：真代码不该出现这个字符串，出现即视为泄漏 */
    private static final String SENTINEL = "ZZQA-SENTINEL-NOT-A-REAL-KEY-9f3a1c-abc-XYZ";
    private static final String MERCHANT_NO = "M20260825MASK";

    @Mock
    private PayOrderService payOrderService;

    @Mock
    private MerchantMapper merchantMapper;

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> appender;
    private Logger controllerLogger;

    @BeforeEach
    void setUp() {
        EpayGatewayController controller = new EpayGatewayController(payOrderService, merchantMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 把一个内存 appender 挂到 controller 的 logger 上，抓它这一轮打出的每一条日志
        controllerLogger = (Logger) LoggerFactory.getLogger(EpayGatewayController.class);
        controllerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (appender != null && controllerLogger != null) {
            controllerLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mapiRequestLogMasksSensitiveParamsAndKeepsDebugFieldsVisible() throws Exception {
        Map<String, String> params = baseParams();
        // 关键：故意塞一个「假密钥」当 key= —— 无论最终能不能通过验签，
        // 只要 controller 那行 params={} 打日志时不打码，哨兵值就会出现在日志里
        params.put("key", SENTINEL);
        params.put("secret", SENTINEL);
        params.put("password", SENTINEL);
        params.put("token", SENTINEL);
        params.put("sign", SENTINEL);

        perform(post("/mapi.php"), params);

        String logs = capturedLogs();

        assertThat(logs)
                .as("每一个敏感字段的原值都不能出现在日志里；哨兵：%s", SENTINEL)
                .doesNotContain(SENTINEL);
        assertThat(logs)
                .as("日志里应能看见「收到请求」这条，确认打的确实是入口日志")
                .contains("/mapi.php 收到请求");
        assertThat(logs).as("商户号要能看见，方便排查").contains(MERCHANT_NO);
        assertThat(logs).as("订单号要能看见").contains("MASK-T-M-1");
        assertThat(logs).as("金额要能看见").contains("1.23");
    }

    @Test
    void submitRequestLogMasksSensitiveParamsAndKeepsDebugFieldsVisible() throws Exception {
        Map<String, String> params = baseParams();
        params.put("key", SENTINEL);
        params.put("api_secret", SENTINEL);
        params.put("password", SENTINEL);
        params.put("access_token", SENTINEL);

        perform(post("/submit.php"), params);

        String logs = capturedLogs();

        assertThat(logs)
                .as("每一个敏感字段的原值都不能出现在日志里；哨兵：%s", SENTINEL)
                .doesNotContain(SENTINEL);
        assertThat(logs).contains("/submit.php 收到请求");
        assertThat(logs).contains(MERCHANT_NO);
        assertThat(logs).contains("MASK-T-M-1");
    }

    @Test
    void apiPhpRequestDoesNotLeakKeyEvenThoughProtocolAllowsPassingItAsParam() throws Exception {
        // /api.php 协议本身允许对接方直接传 key=api_secret 代替签名。如果哪天有人给它补一条
        // "params={}" 日志忘了打码，密钥就会落盘。这条测试守住这个后门。
        Map<String, String> params = new LinkedHashMap<>();
        params.put("act", "order");
        params.put("pid", MERCHANT_NO);
        params.put("out_trade_no", "MASK-T-A-1");
        params.put("key", SENTINEL);
        params.put("sign", SENTINEL);
        params.put("sign_type", "MD5");

        perform(get("/api.php"), params);

        String logs = capturedLogs();
        assertThat(logs)
                .as("/api.php 走通任何分支都不能把 key= 明文落盘")
                .doesNotContain(SENTINEL);
    }

    // ---- helpers ----

    private Map<String, String> baseParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", MERCHANT_NO);
        params.put("out_trade_no", "MASK-T-M-1");
        params.put("type", "wxpay");
        params.put("money", "1.23");
        params.put("name", "MTM-252 打码测试");
        params.put("notify_url", "http://127.0.0.1:1/no-op");
        return params;
    }

    private void perform(MockHttpServletRequestBuilder req, Map<String, String> params) throws Exception {
        req.contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : params.entrySet()) {
            req = req.param(e.getKey(), e.getValue());
        }
        mockMvc.perform(req).andReturn();
    }

    private String capturedLogs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
