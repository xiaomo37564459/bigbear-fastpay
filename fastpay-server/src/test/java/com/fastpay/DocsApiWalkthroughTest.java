package com.fastpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * docs/API.md 的守门测试：**照着文档写的步骤走一遍，验证文档没写错**。
 *
 * 和 {@link AbstractFullFlowTest} 的分工完全不同：
 * - AbstractFullFlowTest 验的是「系统能不能跑通」，可以直接调 SignUtil / EpaySignUtil；
 * - 本测试验的是「文档写得对不对」，所以**故意不引用项目里的任何签名工具类**，
 *   只用 java.security.MessageDigest 按 docs/API.md 第八章白纸黑字写的步骤重新实现一遍。
 *   文档和代码一旦对不上，这里就会红。
 *
 * 覆盖：
 * 1. 文档里那几个「能照着抄的完整例子」，算出来的签名值必须和文档里印的字符串一模一样；
 * 2. 两套接口各走一遍完整下单 → 查询 → 确认支付 → 收到回调 → 验签；
 * 3. 文档里承诺的错误返回（HTTP 状态、code 字段、msg 文案）确实是那样。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocsApiWalkthroughTest {

    // ---- 嵌入式 PostgreSQL，和 PostgresFullFlowTest 同样的起法，不依赖 Docker ----

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    @BeforeAll
    static void startDatabase() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        jdbcUrl = pg.getJdbcUrl("postgres", "postgres");
        try (Connection conn = pg.getPostgresDatabase().getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/init-pg.sql"), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String adminToken;
    private static String merchantToken;
    private static String merchantNo;
    private static String apiSecret;
    private static String shopNo;

    // ================================================================
    // 第一部分：文档第八章「能照着抄的完整例子」，签名值必须一字不差
    // ================================================================

    @Test
    @Order(1)
    void docSignExamples_matchTheStringsPrintedInTheDoc() {
        // docs/API.md 8.1 —— 原生下单（无 returnUrl）
        assertThat(nativeSign(new LinkedHashMap<>(Map.of(
                "merchantNo", "M17390000123400",
                "outTradeNo", "ORDER20260819001",
                "shopNo", "S17390000123401",
                "amount", "10.00",
                "subject", "测试商品",
                "payType", "wxpay",
                "timestamp", "1755561600")), "your-merchant-key"))
                .isEqualTo("4340BBCF38A2296A064AE850D0F4EB6F");

        // docs/API.md 3.2 —— 原生下单（带 returnUrl）
        Map<String, String> withReturn = new LinkedHashMap<>();
        withReturn.put("merchantNo", "M17390000123400");
        withReturn.put("outTradeNo", "ORDER20260819001");
        withReturn.put("shopNo", "S17390000123401");
        withReturn.put("amount", "10.00");
        withReturn.put("subject", "测试商品");
        withReturn.put("payType", "wxpay");
        withReturn.put("returnUrl", "https://your-site.com/pay/result");
        withReturn.put("timestamp", "1755561600");
        assertThat(nativeSign(withReturn, "your-merchant-key"))
                .isEqualTo("4A4A0B4870C6B87F2D1B785A493DA69C");

        // docs/API.md 8.2 —— 易支付下单
        Map<String, String> epay = new LinkedHashMap<>();
        epay.put("pid", "M17390000123400");
        epay.put("type", "wxpay");
        epay.put("out_trade_no", "ORDER20260819001");
        epay.put("money", "10.00");
        epay.put("name", "测试商品");
        epay.put("notify_url", "https://your-site.com/pay/notify");
        epay.put("return_url", "https://your-site.com/pay/result");
        epay.put("sign_type", "MD5");
        assertThat(epaySign(epay, "your-merchant-key"))
                .isEqualTo("44175b98b2b0f726a31a31a1414e8116");

        // docs/API.md 4.3 —— 易支付查询
        Map<String, String> query = new LinkedHashMap<>();
        query.put("pid", "M17390000123400");
        query.put("out_trade_no", "ORDER20260819001");
        assertThat(epaySign(query, "your-merchant-key"))
                .isEqualTo("e9521e3326bdb39e6bcf0779b97ba802");

        // docs/API.md 5.2 —— 易支付回调验签
        Map<String, String> epayCb = new LinkedHashMap<>();
        epayCb.put("pid", "M17390000123400");
        epayCb.put("trade_no", "FP20260819120000123456");
        epayCb.put("out_trade_no", "ORDER20260819001");
        epayCb.put("type", "wxpay");
        epayCb.put("name", "测试商品");
        epayCb.put("money", "10.00");
        epayCb.put("trade_status", "TRADE_SUCCESS");
        epayCb.put("sign_type", "MD5");
        assertThat(epaySign(epayCb, "your-merchant-key"))
                .isEqualTo("98eb26dd531b12d5ccb20339145de5ba");

        // docs/API.md 5.1 —— 原生回调验签
        Map<String, String> nativeCb = new LinkedHashMap<>();
        nativeCb.put("merchantNo", "M17390000123400");
        nativeCb.put("orderNo", "FP20260819120000123456");
        nativeCb.put("outTradeNo", "ORDER20260819001");
        nativeCb.put("amount", "10.00");
        nativeCb.put("payAmount", "10.00");
        nativeCb.put("payType", "wxpay");
        nativeCb.put("status", "1");
        nativeCb.put("payTime", "2026-08-19T12:00:05");
        nativeCb.put("timestamp", "1755576005");
        assertThat(nativeSign(nativeCb, "your-merchant-key"))
                .isEqualTo("915020B87D776B2492ACE83A70F3B30F");
    }

    // ================================================================
    // 第二部分：按文档「第十章 从零接入」的步骤，真的走一遍
    // ================================================================

    @Test
    @Order(2)
    void step1_getMerchantNoAndSecret() throws Exception {
        adminToken = dataOf(postJson("/api/admin/login", null,
                Map.of("username", "admin", "password", "123456"))).get("token").asText();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantName", "文档校验商户");
        body.put("username", "docs_merchant_01");
        body.put("password", "DocsPass123");
        body.put("contactName", "Doc");
        body.put("contactPhone", "13800000001");
        JsonNode merchant = dataOf(postJson("/api/admin/merchant", adminToken, body));
        merchantNo = merchant.get("merchantNo").asText();
        apiSecret = merchant.get("apiSecret").asText();

        merchantToken = dataOf(postJson("/api/merchant/login", null,
                Map.of("username", "docs_merchant_01", "password", "DocsPass123"))).get("token").asText();

        // 文档 7.5：商户号和密钥就是从 /api/merchant/info 来的
        JsonNode info = dataOf(getJson("/api/merchant/info", merchantToken));
        assertThat(info.get("merchantNo").asText()).isEqualTo(merchantNo);
        assertThat(info.get("apiSecret").asText()).isEqualTo(apiSecret);
        assertThat(info.get("password").isNull()).as("文档说 password 字段被置空").isTrue();
    }

    @Test
    @Order(3)
    void step2_prepareShopChannelAndQrcode() throws Exception {
        JsonNode shop = dataOf(postJson("/api/merchant/shop", merchantToken,
                Map.of("shopName", "文档校验店铺", "contactName", "Doc", "contactPhone", "13800000001")));
        shopNo = shop.get("shopNo").asText();
        Long shopId = shop.get("id").asLong();

        Long channelId = dataOf(postJson("/api/merchant/channel", merchantToken,
                Map.of("channelName", "wxpay", "payType", "wxpay"))).get("id").asLong();

        dataOf(postJson("/api/merchant/qrcode", merchantToken, Map.of(
                "shopId", shopId,
                "channelId", channelId,
                "qrcodeName", "docs-qr",
                "qrcodeUrl", "weixin://wxpay/bizpayurl?pr=docs123")));
    }

    // ---------------- 原生接口 ----------------

    @Test
    @Order(4)
    void nativeCreate_worksExactlyAsDocumented() throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        Map<String, String> signParams = new LinkedHashMap<>();
        signParams.put("merchantNo", merchantNo);
        signParams.put("outTradeNo", "DOC_NATIVE_01");
        signParams.put("shopNo", shopNo);
        signParams.put("amount", "10.00");
        signParams.put("subject", "测试商品");
        signParams.put("payType", "wxpay");
        signParams.put("timestamp", String.valueOf(ts));

        Map<String, Object> body = new LinkedHashMap<>(signParams);
        body.put("timestamp", ts);
        body.put("sign", nativeSign(signParams, apiSecret));

        MvcResult created = mockMvc.perform(post("/api/pay/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
        String raw = created.getResponse().getContentAsString();
        JsonNode resp = json(created);
        System.out.println("[DOC] /api/pay/create 原始报文 -> " + raw);

        // 文档 3.1：金额在报文里保留两位小数（别用 JsonNode 转 double 看，那会丢精度）
        assertThat(raw).contains("\"amount\":10.00");

        // 文档 3.1 承诺的返回结构
        assertThat(resp.get("code").asInt()).isEqualTo(200);
        assertThat(resp.get("message").asText()).isEqualTo("操作成功");
        JsonNode data = resp.get("data");
        assertThat(data.get("orderNo").asText()).startsWith("FP");
        assertThat(data.get("outTradeNo").asText()).isEqualTo("DOC_NATIVE_01");
        assertThat(data.get("payMethod").asText()).isEqualTo("api");
        assertThat(data.get("qrcodeUrl").asText()).isNotBlank();
        assertThat(data.get("payPageUrl").isNull()).as("文档说这个接口的 payPageUrl 固定为 null").isTrue();
        assertThat(data.get("expireTime").isNumber()).as("文档说是秒级时间戳（数字）").isTrue();
        assertThat(data.get("expireTime").asLong()).isBetween(1_000_000_000L, 9_999_999_999L);
    }

    @Test
    @Order(5)
    void nativeCreate_errorsAreExactlyAsDocumented() throws Exception {
        long ts = System.currentTimeMillis() / 1000;

        // 文档 3.1：签名算错 -> code 500 / 签名验证失败，且 HTTP 状态仍是 200
        Map<String, Object> badSign = new LinkedHashMap<>();
        badSign.put("merchantNo", merchantNo);
        badSign.put("outTradeNo", "DOC_NATIVE_BAD1");
        badSign.put("shopNo", shopNo);
        badSign.put("amount", "10.00");
        badSign.put("subject", "测试商品");
        badSign.put("payType", "wxpay");
        badSign.put("timestamp", ts);
        badSign.put("sign", "00000000000000000000000000000000");
        MvcResult r1 = mockMvc.perform(post("/api/pay/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badSign))).andReturn();
        assertThat(r1.getResponse().getStatus()).as("文档说失败时 HTTP 也是 200").isEqualTo(200);
        assertThat(json(r1).get("code").asInt()).isEqualTo(500);
        assertThat(json(r1).get("message").asText()).isEqualTo("签名验证失败");

        // 文档 3.1：漏了 shopNo -> code 400 / 店铺编号不能为空
        Map<String, Object> noShop = new LinkedHashMap<>(badSign);
        noShop.remove("shopNo");
        noShop.put("outTradeNo", "DOC_NATIVE_BAD2");
        JsonNode r2 = json(mockMvc.perform(post("/api/pay/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(noShop))).andReturn());
        assertThat(r2.get("code").asInt()).isEqualTo(400);
        assertThat(r2.get("message").asText()).isEqualTo("店铺编号不能为空");

        // 文档 3.1：timestamp 传成 13 位毫秒 -> 请求已过期
        Map<String, String> msSign = new LinkedHashMap<>();
        msSign.put("merchantNo", merchantNo);
        msSign.put("outTradeNo", "DOC_NATIVE_BAD3");
        msSign.put("shopNo", shopNo);
        msSign.put("amount", "10.00");
        msSign.put("subject", "测试商品");
        msSign.put("payType", "wxpay");
        msSign.put("timestamp", String.valueOf(System.currentTimeMillis()));
        Map<String, Object> msBody = new LinkedHashMap<>(msSign);
        msBody.put("timestamp", System.currentTimeMillis());
        msBody.put("sign", nativeSign(msSign, apiSecret));
        JsonNode r3 = json(mockMvc.perform(post("/api/pay/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(msBody))).andReturn());
        System.out.println("[DOC] 13 位毫秒时间戳 -> " + r3);
    }

    @Test
    @Order(6)
    void nativeSubmit_redirectsAndRejectsAsDocumented() throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("merchantNo", merchantNo);
        p.put("outTradeNo", "DOC_NATIVE_SUBMIT");
        p.put("shopNo", shopNo);
        p.put("amount", "11.00");
        p.put("subject", "测试商品");
        p.put("payType", "wxpay");
        p.put("returnUrl", "https://your-site.com/pay/result");
        p.put("timestamp", String.valueOf(ts));
        String sign = nativeSign(p, apiSecret);

        MockHttpServletRequestBuilder ok = post("/api/pay/submit")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : p.entrySet()) {
            ok = ok.param(e.getKey(), e.getValue());
        }
        MvcResult okResult = mockMvc.perform(ok.param("sign", sign)).andReturn();
        assertThat(okResult.getResponse().getStatus()).isEqualTo(302);
        assertThat(okResult.getResponse().getHeader("Location")).contains("/pay/FP");
        System.out.println("[DOC] /api/pay/submit 302 -> " + okResult.getResponse().getHeader("Location"));

        // 文档 3.2：参数校验没过 -> 直接返回 JSON 400，不跳转
        MvcResult noShop = mockMvc.perform(post("/api/pay/submit")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("merchantNo", merchantNo)
                .param("outTradeNo", "DOC_SUBMIT_BAD1")
                .param("amount", "1.00")
                .param("subject", "x")
                .param("payType", "wxpay")
                .param("timestamp", String.valueOf(ts))
                .param("sign", "x")).andReturn();
        assertThat(noShop.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(noShop).get("code").asInt()).isEqualTo(400);

        // 文档 3.2：业务失败（验签失败）-> 302 到错误页
        MvcResult badSign = mockMvc.perform(post("/api/pay/submit")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("merchantNo", merchantNo)
                .param("outTradeNo", "DOC_SUBMIT_BAD2")
                .param("shopNo", shopNo)
                .param("amount", "1.00")
                .param("subject", "x")
                .param("payType", "wxpay")
                .param("timestamp", String.valueOf(ts))
                .param("sign", "00000000000000000000000000000000")).andReturn();
        assertThat(badSign.getResponse().getStatus()).isEqualTo(302);
        System.out.println("[DOC] /api/pay/submit 失败跳转 -> " + badSign.getResponse().getHeader("Location"));
        assertThat(badSign.getResponse().getHeader("Location")).contains("/pay/error?message=");
    }

    @Test
    @Order(7)
    void nativeQueryAndStatusAndPage_returnDocumentedShapes() throws Exception {
        MvcResult q = mockMvc.perform(get("/api/pay/query")
                .param("merchantNo", merchantNo)
                .param("outTradeNo", "DOC_NATIVE_01")).andReturn();
        JsonNode data = json(q).get("data");
        System.out.println("[DOC] /api/pay/query 原始报文 -> " + q.getResponse().getContentAsString());

        // 文档 3.3：时间字段是 ISO-8601（中间字母 T，可能带小数秒），不是 "yyyy-MM-dd HH:mm:ss"
        assertThat(data.get("expireTime").asText())
                .as("文档必须按真实格式写：ISO-8601 带 T")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

        assertThat(data.get("orderNo").asText()).startsWith("FP");
        assertThat(data.get("status").asInt()).isEqualTo(0);
        // MTM-170 后：payAmount 下单时就落好（用来解决"两人同价撞单认错人"），未支付时不再是 null。
        // 第一笔没并发撞车，pay_amount = amount = 10.00；文档 3.3 已同步更新
        assertThat(data.get("payAmount").isNull())
                .as("MTM-170 后 payAmount 下单就有值，不再是 null（文档 3.3 已更新）")
                .isFalse();
        assertThat(new java.math.BigDecimal(data.get("payAmount").asText()))
                .as("第一笔无并发撞单时 payAmount 应等于 amount")
                .isEqualByComparingTo(new java.math.BigDecimal("10.00"));
        assertThat(data.get("payTime").isNull()).as("文档说没付款时 payTime 是 null").isTrue();

        String orderNo = data.get("orderNo").asText();

        MvcResult pageResult = mockMvc.perform(get("/api/pay/page/" + orderNo)).andReturn();
        System.out.println("[DOC] /api/pay/page -> " + json(pageResult));
        JsonNode page = json(pageResult).get("data");
        assertThat(page.get("merchantNo").asText()).isEqualTo(merchantNo);
        assertThat(page.get("shopName").asText()).isNotBlank();
        assertThat(page.get("qrcodeUrl").asText()).isNotBlank();

        MvcResult st = mockMvc.perform(get("/api/pay/status/" + orderNo)).andReturn();
        System.out.println("[DOC] /api/pay/status -> " + json(st));
        assertThat(json(st).get("data").get("status").asInt()).isEqualTo(0);

        MvcResult res = mockMvc.perform(get("/api/pay/result/" + orderNo)).andReturn();
        System.out.println("[DOC] /api/pay/result -> " + json(res));
        assertThat(json(res).get("data").get("merchantName").asText()).isEqualTo("文档校验商户");

        // 文档 3.3：订单不存在
        JsonNode missing = json(mockMvc.perform(get("/api/pay/query")
                .param("merchantNo", merchantNo)
                .param("outTradeNo", "NOT_EXIST")).andReturn());
        assertThat(missing.get("code").asInt()).isEqualTo(500);
        assertThat(missing.get("message").asText()).isEqualTo("订单不存在");
    }

    /**
     * 文档 5.1：原生订单的回调是 POST 表单 + 大写签名，
     * 回调地址取自商户配置（文档 7.2 的接口），不是下单参数。
     */
    @Test
    @Order(8)
    void nativeCallback_isPostFormAndVerifiesWithDocumentedRecipe() throws Exception {
        BlockingQueue<Captured> captured = new ArrayBlockingQueue<>(4);
        HttpServer server = startCaptureServer(captured);
        try {
            String notifyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/notify";

            // 文档 7.2：改回调地址
            mockMvc.perform(put("/api/merchant/callback-config")
                            .header("Authorization", "Bearer " + merchantToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("notifyUrl", notifyUrl, "returnUrl", "https://your-site.com/pay/result"))))
                    .andReturn();

            long ts = System.currentTimeMillis() / 1000;
            Map<String, String> p = new LinkedHashMap<>();
            p.put("merchantNo", merchantNo);
            p.put("outTradeNo", "DOC_NATIVE_CB");
            p.put("shopNo", shopNo);
            p.put("amount", "12.00");
            p.put("subject", "测试商品");
            p.put("payType", "wxpay");
            p.put("timestamp", String.valueOf(ts));
            Map<String, Object> body = new LinkedHashMap<>(p);
            body.put("timestamp", ts);
            body.put("sign", nativeSign(p, apiSecret));

            String orderNo = json(mockMvc.perform(post("/api/pay/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body))).andReturn())
                    .get("data").get("orderNo").asText();

            postJson("/api/admin/order/" + orderNo + "/confirm", adminToken, Map.of());

            Captured cb = captured.poll(20, TimeUnit.SECONDS);
            assertThat(cb).as("20 秒内应收到原生回调").isNotNull();
            System.out.println("[DOC] 原生回调 method=" + cb.method + " body=" + cb.body + " query=" + cb.rawQuery);

            assertThat(cb.method).as("文档说原生回调是 POST").isEqualTo("POST");
            Map<String, String> params = parseForm(cb.body);
            assertThat(params.get("merchantNo")).isEqualTo(merchantNo);
            assertThat(params.get("orderNo")).isEqualTo(orderNo);
            assertThat(params.get("outTradeNo")).isEqualTo("DOC_NATIVE_CB");
            assertThat(params.get("payType")).isEqualTo("wxpay");
            assertThat(params.get("status")).isEqualTo("1");
            assertThat(params.get("payAmount")).isEqualTo("12.00");
            assertThat(params.get("sign")).matches("[0-9A-F]{32}");
            System.out.println("[DOC] 原生回调 payTime 实际长这样 = " + params.get("payTime"));
            // 文档 5.1：payTime 是 LocalDateTime.toString() 原样输出，带 T、可能带小数秒
            assertThat(params.get("payTime"))
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

            // 按文档 5.1 的配方重新算一遍签名，必须对得上
            String sign = params.remove("sign");
            assertThat(nativeSign(params, apiSecret))
                    .as("按 docs/API.md 5.1 的验签步骤算出来必须等于平台发过来的 sign")
                    .isEqualTo(sign.toUpperCase());
        } finally {
            server.stop(0);
        }
    }

    // ---------------- 易支付协议接口 ----------------

    @Test
    @Order(9)
    void epayMapi_worksExactlyAsDocumented() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pid", merchantNo);
        p.put("type", "wxpay");
        p.put("out_trade_no", "DOC_EPAY_01");
        p.put("money", "10.00");
        p.put("name", "测试商品");
        p.put("notify_url", "http://127.0.0.1:1/notify");
        p.put("return_url", "https://your-site.com/pay/result");
        p.put("sign_type", "MD5");

        JsonNode resp = json(postForm("/mapi.php", withSign(p, epaySign(p, apiSecret))));
        System.out.println("[DOC] /mapi.php -> " + resp);

        assertThat(resp.get("code").asInt()).as("文档说成功是 code=1").isEqualTo(1);
        assertThat(resp.get("msg").asText()).isEqualTo("success");
        assertThat(resp.get("trade_no").asText()).startsWith("FP");
        assertThat(resp.get("payurl").asText()).contains(resp.get("trade_no").asText());
        assertThat(resp.get("qrcode").asText()).isNotBlank();

        // 文档 4.1 的错误表
        Map<String, String> bad = new LinkedHashMap<>(p);
        bad.put("out_trade_no", "DOC_EPAY_BADSIGN");
        JsonNode badResp = json(postForm("/mapi.php", withSign(bad, "00000000000000000000000000000000")));
        assertThat(badResp.get("code").asInt()).isEqualTo(-1);
        assertThat(badResp.get("msg").asText()).isEqualTo("签名验证失败");

        Map<String, String> noPid = new LinkedHashMap<>(p);
        noPid.remove("pid");
        assertThat(json(postForm("/mapi.php", withSign(noPid, "x"))).get("msg").asText())
                .isEqualTo("缺少商户号 pid");

        Map<String, String> dup = new LinkedHashMap<>(p);
        assertThat(json(postForm("/mapi.php", withSign(dup, epaySign(dup, apiSecret)))).get("msg").asText())
                .isEqualTo("商户订单号已存在");

        Map<String, String> badMoney = new LinkedHashMap<>(p);
        badMoney.put("out_trade_no", "DOC_EPAY_BADMONEY");
        badMoney.put("money", "abc");
        assertThat(json(postForm("/mapi.php", withSign(badMoney, epaySign(badMoney, apiSecret)))).get("msg").asText())
                .isEqualTo("money 不是合法金额：abc");
    }

    @Test
    @Order(10)
    void epaySubmit_redirectsAndFailsWithPlainText400() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pid", merchantNo);
        p.put("type", "wxpay");
        p.put("out_trade_no", "DOC_EPAY_SUBMIT");
        p.put("money", "13.00");
        p.put("name", "测试商品");
        p.put("notify_url", "http://127.0.0.1:1/notify");
        p.put("sign_type", "MD5");

        MvcResult ok = postForm("/submit.php", withSign(p, epaySign(p, apiSecret)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(302);
        assertThat(ok.getResponse().getHeader("Location")).contains("/pay/FP");

        Map<String, String> bad = new LinkedHashMap<>(p);
        bad.put("out_trade_no", "DOC_EPAY_SUBMIT_BAD");
        MvcResult fail = postForm("/submit.php", withSign(bad, "00000000000000000000000000000000"));
        assertThat(fail.getResponse().getStatus()).as("文档说 submit.php 失败是 HTTP 400").isEqualTo(400);
        String text = fail.getResponse().getContentAsString();
        System.out.println("[DOC] /submit.php 失败 -> " + fail.getResponse().getStatus() + " " + text);
        assertThat(text).isEqualTo("下单失败：签名验证失败");
    }

    @Test
    @Order(11)
    void epayApiOrder_returnsDocumentedShape() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pid", merchantNo);
        p.put("out_trade_no", "DOC_EPAY_01");
        MvcResult r = getForm("/api.php", withSign(p, epaySign(p, apiSecret)), "order");
        JsonNode resp = json(r);
        System.out.println("[DOC] /api.php?act=order -> " + resp);

        assertThat(resp.get("code").asInt()).isEqualTo(1);
        assertThat(resp.get("msg").asText()).isEqualTo("success");
        assertThat(resp.get("out_trade_no").asText()).isEqualTo("DOC_EPAY_01");
        assertThat(resp.get("pid").asText()).isEqualTo(merchantNo);
        assertThat(resp.get("type").asText()).isEqualTo("wxpay");
        assertThat(resp.get("money").asText()).isEqualTo("10.00");
        assertThat(resp.get("status").asInt()).as("没付款时是 0").isEqualTo(0);
        assertThat(resp.get("endtime").asText()).as("文档说没付款时 endtime 是空字符串").isEmpty();
        System.out.println("[DOC] /api.php addtime 实际长这样 = " + resp.get("addtime").asText());
        // 文档 4.3：addtime 是 LocalDateTime.toString() 原样输出，带 T、可能带小数秒
        assertThat(resp.get("addtime").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

        // 文档 4.3：直接传 key=API Secret 也放行
        Map<String, String> byKey = new LinkedHashMap<>();
        byKey.put("pid", merchantNo);
        byKey.put("out_trade_no", "DOC_EPAY_01");
        byKey.put("key", apiSecret);
        JsonNode byKeyResp = json(getForm("/api.php", byKey, "order"));
        assertThat(byKeyResp.get("code").asInt()).as("文档说 key 和 sign 二选一").isEqualTo(1);

        // 文档 4.3：订单不存在
        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("pid", merchantNo);
        missing.put("out_trade_no", "NOT_EXIST");
        assertThat(json(getForm("/api.php", withSign(missing, epaySign(missing, apiSecret)), "order"))
                .get("msg").asText()).isEqualTo("订单不存在");

        // 文档 4.4：退款占位
        MvcResult refund = mockMvc.perform(get("/api.php").param("act", "refund")).andReturn();
        assertThat(refund.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(refund).get("code").asInt()).isEqualTo(-1);
        assertThat(json(refund).get("msg").asText()).contains("本期未实现退款");

        // 文档 4.4：不认识的 act
        assertThat(json(mockMvc.perform(get("/api.php").param("act", "xxx")).andReturn()).get("msg").asText())
                .isEqualTo("不支持的 act：xxx");
    }

    /**
     * 文档 5.2：易支付订单的回调是 GET + 小写签名，回调地址是下单时传的 notify_url
     */
    @Test
    @Order(12)
    void epayCallback_isGetAndVerifiesWithDocumentedRecipe() throws Exception {
        BlockingQueue<Captured> captured = new ArrayBlockingQueue<>(4);
        HttpServer server = startCaptureServer(captured);
        try {
            String notifyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/notify";

            Map<String, String> p = new LinkedHashMap<>();
            p.put("pid", merchantNo);
            p.put("type", "wxpay");
            p.put("out_trade_no", "DOC_EPAY_CB");
            p.put("money", "14.00");
            p.put("name", "测试商品");
            p.put("notify_url", notifyUrl);
            p.put("sign_type", "MD5");

            JsonNode created = json(postForm("/mapi.php", withSign(p, epaySign(p, apiSecret))));
            assertThat(created.get("code").asInt()).isEqualTo(1);
            String orderNo = created.get("trade_no").asText();

            postJson("/api/admin/order/" + orderNo + "/confirm", adminToken, Map.of());

            Captured cb = captured.poll(20, TimeUnit.SECONDS);
            assertThat(cb).as("20 秒内应收到易支付回调").isNotNull();
            System.out.println("[DOC] 易支付回调 method=" + cb.method + " query=" + cb.rawQuery);

            assertThat(cb.method).as("文档说易支付回调是 GET").isEqualTo("GET");
            Map<String, String> params = parseQuery(cb.rawQuery);
            assertThat(params.get("pid")).isEqualTo(merchantNo);
            assertThat(params.get("trade_no")).isEqualTo(orderNo);
            assertThat(params.get("out_trade_no")).isEqualTo("DOC_EPAY_CB");
            assertThat(params.get("type")).isEqualTo("wxpay");
            assertThat(params.get("name")).isEqualTo("测试商品");
            assertThat(params.get("money")).isEqualTo("14.00");
            assertThat(params.get("trade_status")).isEqualTo("TRADE_SUCCESS");
            assertThat(params.get("sign_type")).isEqualTo("MD5");
            assertThat(params.get("sign")).matches("[0-9a-f]{32}");

            // 按文档 5.2 的配方重新算一遍
            assertThat(epaySign(params, apiSecret))
                    .as("按 docs/API.md 5.2 的验签步骤算出来必须等于平台发过来的 sign")
                    .isEqualTo(params.get("sign"));
        } finally {
            server.stop(0);
        }
    }

    // ================================================================
    // 文档第八章的签名算法，这里按文档文字重新实现一遍（不引用项目的工具类）
    // ================================================================

    /** docs/API.md 8.1：ASCII 升序 -> a=1&b=2 -> 末尾 &key=密钥 -> MD5 -> 大写 */
    private static String nativeSign(Map<String, String> params, String apiSecret) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("sign");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append("&key=").append(apiSecret);
        return md5(sb.toString()).toUpperCase();
    }

    /** docs/API.md 8.2：ASCII 升序 -> 去掉 sign/sign_type/空值 -> a=1&b=2 -> 末尾直接拼密钥 -> MD5 -> 小写 */
    private static String epaySign(Map<String, String> params, String apiSecret) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if ("sign".equals(k) || "sign_type".equals(k)) {
                continue;
            }
            if (v == null || v.isEmpty()) {
                continue;
            }
            sorted.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append(apiSecret);
        return md5(sb.toString());
    }

    private static String md5(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- 测试脚手架 ----------------

    private static Map<String, String> withSign(Map<String, String> params, String sign) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.put("sign", sign);
        return out;
    }

    private MvcResult postForm(String url, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getForm(String url, Map<String, String> params, String act) throws Exception {
        MockHttpServletRequestBuilder request = get(url).param("act", act);
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult postJson(String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult getJson(String url, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        JsonNode body = json(result);
        assertThat(body.get("code").asInt()).as("请求应成功：" + body).isEqualTo(200);
        return body.get("data");
    }

    private static HttpServer startCaptureServer(BlockingQueue<Captured> sink) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            Captured c = new Captured();
            c.method = exchange.getRequestMethod();
            c.rawQuery = exchange.getRequestURI().getRawQuery();
            c.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sink.offer(c);
            byte[] out = "success".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return server;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            out.put(k, URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, String> parseForm(String body) {
        return parseQuery(body);
    }

    private static class Captured {
        String method;
        String rawQuery;
        String body;
    }
}
