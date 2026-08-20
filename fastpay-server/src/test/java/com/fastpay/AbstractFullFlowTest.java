package com.fastpay;

import com.fastpay.service.PayNotifyService;
import com.fastpay.service.PayOrderService;
import com.fastpay.util.EpaySignUtil;
import com.fastpay.util.SignUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付系统端到端主流程测试，MySQL 和 PostgreSQL 各跑一遍同一套步骤（两个子类分别提供真实数据库）：
 * 建表 -> 管理员登录 -> 建商户并登录 -> 建店铺/通道/二维码 -> 下单到支付页 -> 模拟支付成功回调 ->
 * 订单变已支付 -> 超时自动关单 -> 管理端/商户端统计对数 -> 各列表分页。
 *
 * 两个数据库都返回 HTTP 200，业务成功/失败只看响应体里的 code 字段（GlobalExceptionHandler 没有用
 * @ResponseStatus 改写状态码），所以本类所有断言都基于 jsonPath("$.code") 和响应体内容，不看 HTTP 状态行。
 *
 * webEnvironment 用 RANDOM_PORT 而不是默认的 MOCK：WebSocketConfig 里的 ServerEndpointExporter
 * 需要一个真的 servlet 容器才能拿到 jakarta.websocket.server.ServerContainer，MOCK 环境没有真容器，
 * 启动就会报 "ServerContainer not available"。RANDOM_PORT 会真的起一个内嵌 Tomcat，MockMvc 请求仍然走
 * 内存里的 mock 分发，不需要额外配置。
 *
 * 注意：这里故意不用 @TestInstance(PER_CLASS)。PER_CLASS 会让 JUnit 在跑 @BeforeAll 之前就先创建测试
 * 实例，而 Spring 的 ApplicationContext 是跟着实例创建一起加载的——子类 @BeforeAll 里启动的数据库还没
 * 起来，Spring 就已经在用 @DynamicPropertySource 读数据库连接信息了，会连到错误的地址上。所以保持默认
 * 的 PER_METHOD 生命周期（@BeforeAll 必须是 static，本来就是），跨步骤共享的状态改用 static 字段。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractFullFlowTest {

    private static final String MERCHANT_USERNAME = "it_merchant_01";
    private static final String MERCHANT_PASSWORD = "TestPass123";
    private static final DateTimeFormatter RECEIVE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PayNotifyService payNotifyService;

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private DataSource dataSource;

    private static String adminToken;
    private static String merchantToken;
    private static Long merchantId;
    private static String merchantNo;
    private static String merchantApiSecret;
    private static Long shopId;
    private static String shopNo;
    private static Long channelId;
    private static String firstOrderNo;
    private static String secondOrderNo;
    private static String epayCallbackOrderNo;

    @Test
    @Order(1)
    void step1_adminCanLoginWithSeedAccount() throws Exception {
        // 管理员账号不再由 SQL 脚本预置，改由后端 InitConfig 在启动时读 fastpay.admin.username / password
        // 创建（见 application-dev.yml，dev 环境固定为 admin / 123456，方便测试）
        JsonNode data = dataOf(postJson("/api/admin/login", null,
                Map.of("username", "admin", "password", "123456")));

        adminToken = data.get("token").asText();
        assertThat(adminToken).isNotBlank();
        assertThat(data.get("userType").asText()).isEqualTo("admin");
    }

    @Test
    @Order(2)
    void step2_adminCreatesMerchantAndMerchantCanLogin() throws Exception {
        Map<String, Object> createMerchantBody = new LinkedHashMap<>();
        createMerchantBody.put("merchantName", "IT Test Merchant");
        createMerchantBody.put("username", MERCHANT_USERNAME);
        createMerchantBody.put("password", MERCHANT_PASSWORD);
        createMerchantBody.put("contactName", "Tester");
        createMerchantBody.put("contactPhone", "13800000000");

        JsonNode merchant = dataOf(postJson("/api/admin/merchant", adminToken, createMerchantBody));
        merchantId = merchant.get("id").asLong();
        merchantNo = merchant.get("merchantNo").asText();
        merchantApiSecret = merchant.get("apiSecret").asText();
        assertThat(merchantId).isPositive();
        assertThat(merchantNo).isNotBlank();
        assertThat(merchantApiSecret).isNotBlank();

        JsonNode loginData = dataOf(postJson("/api/merchant/login", null,
                Map.of("username", MERCHANT_USERNAME, "password", MERCHANT_PASSWORD)));
        merchantToken = loginData.get("token").asText();
        assertThat(merchantToken).isNotBlank();
        assertThat(loginData.get("userType").asText()).isEqualTo("merchant");
    }

    @Test
    @Order(3)
    void step3_merchantSetsUpShopChannelAndQrcode() throws Exception {
        Map<String, Object> shopBody = new LinkedHashMap<>();
        shopBody.put("shopName", "IT Test Shop");
        shopBody.put("description", "shop for integration test");
        shopBody.put("contactName", "Tester");
        shopBody.put("contactPhone", "13800000000");
        JsonNode shop = dataOf(postJson("/api/merchant/shop", merchantToken, shopBody));
        shopId = shop.get("id").asLong();
        shopNo = shop.get("shopNo").asText();
        assertThat(shopId).isPositive();
        assertThat(shopNo).isNotBlank();

        Map<String, Object> channelBody = new LinkedHashMap<>();
        channelBody.put("channelName", "wxpay channel");
        channelBody.put("payType", "wxpay");
        JsonNode channel = dataOf(postJson("/api/merchant/channel", merchantToken, channelBody));
        channelId = channel.get("id").asLong();
        assertThat(channelId).isPositive();

        Map<String, Object> qrcodeBody = new LinkedHashMap<>();
        qrcodeBody.put("shopId", shopId);
        qrcodeBody.put("channelId", channelId);
        qrcodeBody.put("qrcodeName", "qrcode-1");
        qrcodeBody.put("qrcodeUrl", "weixin://wxpay/bizpayurl?pr=testcode123");
        JsonNode qrcode = dataOf(postJson("/api/merchant/qrcode", merchantToken, qrcodeBody));
        assertThat(qrcode.get("id").asLong()).isPositive();
    }

    @Test
    @Order(4)
    void step4_createOrderReachesPayPage() throws Exception {
        firstOrderNo = createSignedOrder("IT0000000001", "0.01", "test order 1");

        JsonNode payPage = dataOf(getJson("/api/pay/page/" + firstOrderNo, null));
        assertThat(payPage.get("status").asInt()).isEqualTo(0); // 待支付
        assertThat(payPage.get("qrcodeUrl").asText()).isNotBlank();
        assertThat(payPage.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("0.01"));
    }

    @Test
    @Order(5)
    void step5_notifyCallbackMarksOrderPaid() throws Exception {
        sendWxPayNotify("0.01");

        JsonNode status = dataOf(getJson("/api/pay/status/" + firstOrderNo, null));
        assertThat(status.get("status").asInt()).isEqualTo(1); // 已支付
        assertThat(status.get("payAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("0.01"));
    }

    @Test
    @Order(6)
    void step6_dashboardStatsMatchAfterPayment() throws Exception {
        JsonNode adminDashboard = dataOf(getJson("/api/admin/dashboard", adminToken));
        assertThat(adminDashboard.get("todayOrderCount").asInt()).isEqualTo(1);
        assertThat(adminDashboard.get("todaySuccessCount").asInt()).isEqualTo(1);
        assertThat(adminDashboard.get("todayAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(adminDashboard.get("totalOrderCount").asLong()).isEqualTo(1L);
        assertThat(adminDashboard.get("totalSuccessCount").asLong()).isEqualTo(1L);
        assertThat(adminDashboard.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(adminDashboard.get("merchantCount").asLong()).isEqualTo(1L);
        assertThat(adminDashboard.get("shopCount").asLong()).isEqualTo(1L);
        assertThat(adminDashboard.get("qrcodeCount").asLong()).isEqualTo(1L);
        assertThat(adminDashboard.get("wxpayCount").asLong()).isEqualTo(1L);

        // recentStats 的 SQL 别名在 Mapper 里改成了 stat_date（PG 里 date 是关键字），
        // 这里断言接口对外字段名还是 date，没有把这个改动漏到前端头上
        JsonNode recentStats = adminDashboard.get("recentStats");
        assertThat(recentStats.isArray()).isTrue();
        assertThat(recentStats.size()).isGreaterThan(0);
        JsonNode today = recentStats.get(0);
        assertThat(today.has("date")).isTrue();
        assertThat(today.has("stat_date")).isFalse();

        JsonNode merchantDashboard = dataOf(getJson("/api/merchant/dashboard", merchantToken));
        assertThat(merchantDashboard.get("todayOrderCount").asInt()).isEqualTo(1);
        assertThat(merchantDashboard.get("todaySuccessCount").asInt()).isEqualTo(1);
        assertThat(merchantDashboard.get("todayAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(merchantDashboard.get("shopCount").asLong()).isEqualTo(1L);
        assertThat(merchantDashboard.get("qrcodeCount").asLong()).isEqualTo(1L);
    }

    @Test
    @Order(7)
    void step7_expiredOrderAutoCloses() throws Exception {
        secondOrderNo = createSignedOrder("IT0000000002", "0.02", "test order 2");

        // 把这笔订单的过期时间直接改到过去，模拟"已经超时"，而不用真的等 3 分钟
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE fp_pay_order SET expire_time = ? WHERE order_no = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
            ps.setString(2, secondOrderNo);
            ps.executeUpdate();
        }

        // 这就是 NotifyRetryTask 每 5 分钟自动调用的同一个方法，这里直接同步调用一次
        payOrderService.processExpiredOrders();

        JsonNode status = dataOf(getJson("/api/pay/status/" + secondOrderNo, null));
        assertThat(status.get("status").asInt()).isEqualTo(2); // 已过期（超时自动关闭）
    }

    @Test
    @Order(9)
    void step9_epayMapiCreatesOrderWithEpaySource() throws Exception {
        // sub2api 视角：POST /mapi.php，签名用易支付协议
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", merchantNo);
        params.put("out_trade_no", "EPAY0001");
        params.put("type", "wxpay");
        params.put("name", "epay 测试商品");
        params.put("money", "1.23");
        params.put("notify_url", "http://127.0.0.1:1/mock-notify"); // 本步不校验回调，随便填
        params.put("return_url", "http://example.com/back");
        params.put("clientip", "127.0.0.1");
        String sign = EpaySignUtil.generateSign(params, merchantApiSecret);
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        JsonNode resp = objectMapper.readTree(postForm("/mapi.php", params).getResponse().getContentAsByteArray());
        assertThat(resp.get("code").asInt()).isEqualTo(1);
        String epayTradeNo = resp.get("trade_no").asText();
        assertThat(epayTradeNo).isNotBlank();
        assertThat(resp.get("payurl").asText()).contains(epayTradeNo);

        // 数据库里这单必须是 order_source=epay，回调走 GET 格式
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT order_source, notify_url FROM fp_pay_order WHERE order_no = ?")) {
            ps.setString(1, epayTradeNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("order_source")).isEqualTo("epay");
                assertThat(rs.getString("notify_url")).isEqualTo("http://127.0.0.1:1/mock-notify");
            }
        }
    }

    @Test
    @Order(10)
    void step10_epayMapiRejectsBadSign() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", merchantNo);
        params.put("out_trade_no", "EPAY_BAD");
        params.put("type", "wxpay");
        params.put("name", "bad sign");
        params.put("money", "0.01");
        params.put("notify_url", "http://x/notify");
        params.put("sign", "00000000000000000000000000000000");
        params.put("sign_type", "MD5");

        JsonNode resp = objectMapper.readTree(postForm("/mapi.php", params).getResponse().getContentAsByteArray());
        assertThat(resp.get("code").asInt()).isEqualTo(-1);
        assertThat(resp.get("msg").asText()).contains("签名");
    }

    @Test
    @Order(11)
    void step11_epaySubmitRedirectsToPayPage() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", merchantNo);
        params.put("out_trade_no", "EPAY0002");
        params.put("type", "wxpay");
        params.put("name", "submit 测试");
        params.put("money", "2.34");
        params.put("notify_url", "http://x/notify2");
        params.put("return_url", "http://example.com/ok");
        String sign = EpaySignUtil.generateSign(params, merchantApiSecret);
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        MockHttpServletRequestBuilder request = post("/submit.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        MvcResult r = mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = r.getResponse().getHeader("Location");
        assertThat(location).contains("/pay/FP");
    }

    @Test
    @Order(12)
    void step12_epayApiOrderQueryReturnsStatus() throws Exception {
        // 上面 EPAY0001 已下单但没支付，查询应返回 status=0
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", merchantNo);
        params.put("out_trade_no", "EPAY0001");
        String sign = EpaySignUtil.generateSign(params, merchantApiSecret);
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        MockHttpServletRequestBuilder request = get("/api.php").param("act", "order");
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        JsonNode resp = objectMapper.readTree(r.getResponse().getContentAsByteArray());
        assertThat(resp.get("code").asInt()).isEqualTo(1);
        assertThat(resp.get("out_trade_no").asText()).isEqualTo("EPAY0001");
        assertThat(resp.get("status").asInt()).isEqualTo(0);
        assertThat(resp.get("pid").asText()).isEqualTo(merchantNo);
    }

    @Test
    @Order(12)
    void step12b_epayApiRefundReturnsExplicitUnsupported() throws Exception {
        // sub2api 二进制里硬编码了 /api.php?act=refund。本期不做退款，
        // 但必须给一个"明确不支持"的响应，不能 404 / 500 让对方误以为网络异常
        MvcResult get = mockMvc.perform(get("/api.php").param("act", "refund")).andReturn();
        JsonNode getResp = objectMapper.readTree(get.getResponse().getContentAsByteArray());
        assertThat(get.getResponse().getStatus()).isEqualTo(200);
        assertThat(getResp.get("code").asInt()).isEqualTo(-1);
        assertThat(getResp.get("msg").asText()).contains("退款");

        // sub2api 可能用 POST（strings 里没标方法，两个方法都要接得住）
        MvcResult post = mockMvc.perform(post("/api.php").param("act", "refund")).andReturn();
        JsonNode postResp = objectMapper.readTree(post.getResponse().getContentAsByteArray());
        assertThat(post.getResponse().getStatus()).isEqualTo(200);
        assertThat(postResp.get("code").asInt()).isEqualTo(-1);
        assertThat(postResp.get("msg").asText()).contains("退款");
    }

    @Test
    @Order(13)
    void step13_epaySourceOrderCallbackIsGetWithEpayFormat() throws Exception {
        // 起一个本地 HTTP 服务捕获真实回调，验证：
        //   1) 请求方法是 GET（不是 POST）
        //   2) 查询串里带上 pid/trade_no/out_trade_no/type/money/trade_status/sign/sign_type
        //   3) 签名能用易支付算法验证通过
        BlockingQueue<CapturedRequest> captured = new ArrayBlockingQueue<>(4);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/notify", exchange -> {
                CapturedRequest req = new CapturedRequest();
                req.method = exchange.getRequestMethod();
                req.rawQuery = exchange.getRequestURI().getRawQuery();
                captured.offer(req);
                byte[] body = "success".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            int port = server.getAddress().getPort();
            String notifyUrl = "http://127.0.0.1:" + port + "/notify";

            // 下一笔易支付订单，notify_url 指向本地捕获服务
            Map<String, String> params = new LinkedHashMap<>();
            params.put("pid", merchantNo);
            params.put("out_trade_no", "EPAY_CB01");
            params.put("type", "wxpay");
            params.put("name", "callback flow");
            params.put("money", "3.45");
            params.put("notify_url", notifyUrl);
            String sign = EpaySignUtil.generateSign(params, merchantApiSecret);
            params.put("sign", sign);
            params.put("sign_type", "MD5");

            JsonNode create = objectMapper.readTree(postForm("/mapi.php", params).getResponse().getContentAsByteArray());
            assertThat(create.get("code").asInt()).isEqualTo(1);
            String epayOrderNo = create.get("trade_no").asText();
            epayCallbackOrderNo = epayOrderNo;

            // 用管理员权限手动确认支付，触发异步回调
            postJson("/api/admin/order/" + epayOrderNo + "/confirm", adminToken, Map.of());

            CapturedRequest cb = captured.poll(15, TimeUnit.SECONDS);
            assertThat(cb).as("在 15s 内应收到易支付回调").isNotNull();
            assertThat(cb.method).isEqualTo("GET");
            assertThat(cb.rawQuery).isNotBlank();

            Map<String, String> parsed = parseQuery(cb.rawQuery);
            assertThat(parsed.get("pid")).isEqualTo(merchantNo);
            assertThat(parsed.get("trade_no")).isEqualTo(epayOrderNo);
            assertThat(parsed.get("out_trade_no")).isEqualTo("EPAY_CB01");
            assertThat(parsed.get("type")).isEqualTo("wxpay");
            assertThat(parsed.get("money")).isEqualTo("3.45");
            assertThat(parsed.get("trade_status")).isEqualTo("TRADE_SUCCESS");
            assertThat(parsed.get("sign_type")).isEqualTo("MD5");
            assertThat(parsed.get("sign")).isNotBlank();

            // 用易支付算法反过来校验签名，确认服务端签得对
            assertThat(EpaySignUtil.verifySign(parsed, merchantApiSecret)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Order(14)
    void step14_successfulNotifyPersistsResponseAndOrderSource() throws Exception {
        // 自建本地 HttpServer 处理一次真实回调（返回 "success"），确保它落库后再关服务器
        // ——不复用 step13 的服务，因为那里在 poll 到请求后立即 stop(0)，会把回写数据库的最后一段掐掉。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String successOrderNo;
        try {
            server.createContext("/notify", exchange -> {
                byte[] body = "success".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            int port = server.getAddress().getPort();
            String notifyUrl = "http://127.0.0.1:" + port + "/notify";

            Map<String, String> params = new LinkedHashMap<>();
            params.put("pid", merchantNo);
            params.put("out_trade_no", "EPAY_OK01");
            params.put("type", "wxpay");
            params.put("name", "success 回调用例");
            params.put("money", "0.12");
            params.put("notify_url", notifyUrl);
            String sign = EpaySignUtil.generateSign(params, merchantApiSecret);
            params.put("sign", sign);
            params.put("sign_type", "MD5");

            JsonNode create = objectMapper.readTree(postForm("/mapi.php", params).getResponse().getContentAsByteArray());
            assertThat(create.get("code").asInt()).isEqualTo(1);
            successOrderNo = create.get("trade_no").asText();

            postJson("/api/admin/order/" + successOrderNo + "/confirm", adminToken, Map.of());
            pollUntilNotifyCountAtLeast(successOrderNo, 1);
        } finally {
            server.stop(1);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT notify_status, notify_count, notify_result, notify_error, order_source " +
                             "FROM fp_pay_order WHERE order_no = ?")) {
            ps.setString(1, successOrderNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("notify_status")).isEqualTo(1); // 通知成功
                assertThat(rs.getInt("notify_count")).isGreaterThanOrEqualTo(1);
                assertThat(rs.getString("notify_result")).isEqualTo("success");
                assertThat(rs.getString("notify_error")).isNull();
                assertThat(rs.getString("order_source")).isEqualTo("epay");
            }
        }

        // 走一遍管理后台的详情 API，确认这两个字段真的通过 JSON 出去，不只是躺在数据库里
        JsonNode adminSuccess = dataOf(getJson("/api/admin/order/" + successOrderNo, adminToken));
        assertThat(adminSuccess.get("notifyResult").asText()).isEqualTo("success");
        assertThat(adminSuccess.get("notifyError").isNull()).isTrue();
    }

    @Test
    @Order(15)
    void step15_failedNotifyPersistsErrorMessage() throws Exception {
        // 造一笔易支付订单，notify_url 指到一个绝对连不上的端口（127.0.0.1:1，特权端口，本地也不会有人监听）
        // 触发一次确认支付 → 异步回调 → 回调失败 → 错误信息应落库
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", merchantNo);
        params.put("out_trade_no", "EPAY_FAIL01");
        params.put("type", "wxpay");
        params.put("name", "回调失败用例");
        params.put("money", "0.11");
        params.put("notify_url", "http://127.0.0.1:1/definitely-unreachable");
        String sign = EpaySignUtil.generateSign(params, merchantApiSecret);
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        JsonNode create = objectMapper.readTree(postForm("/mapi.php", params).getResponse().getContentAsByteArray());
        assertThat(create.get("code").asInt()).isEqualTo(1);
        String failOrderNo = create.get("trade_no").asText();

        postJson("/api/admin/order/" + failOrderNo + "/confirm", adminToken, Map.of());

        // 等回调线程把失败信息写进库
        pollUntilNotifyCountAtLeast(failOrderNo, 1);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT notify_status, notify_result, notify_error FROM fp_pay_order WHERE order_no = ?")) {
            ps.setString(1, failOrderNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("notify_status")).isEqualTo(2); // 通知失败
                assertThat(rs.getString("notify_result")).isNull();
                String err = rs.getString("notify_error");
                assertThat(err).as("失败原因必须落库").isNotBlank();
                // 无法预知不同 JDK 上具体是 ConnectException 还是 SocketException，但一定包含 "Connection" 或 "connect" 字样
                assertThat(err.toLowerCase()).containsAnyOf("connect", "refused", "拒绝", "socket");
            }
        }

        // 商户中心侧走一遍详情 API（本商户看本商户订单），确认 notifyError 也通过 JSON 出去
        JsonNode merchantFail = dataOf(getJson("/api/merchant/order/" + failOrderNo, merchantToken));
        assertThat(merchantFail.get("notifyError").asText().toLowerCase()).containsAnyOf("connect", "refused", "socket");
        assertThat(merchantFail.get("notifyResult").isNull()).isTrue();
    }

    @Test
    @Order(16)
    void step16_adminOrderDetailReturnsMerchantShopAndSource() throws Exception {
        // 详情接口口径要跟列表一致，返回 merchantName / shopName / orderSource
        JsonNode admin = dataOf(getJson("/api/admin/order/" + firstOrderNo, adminToken));
        assertThat(admin.get("merchantName").asText()).isEqualTo("IT Test Merchant");
        assertThat(admin.get("shopName").asText()).isEqualTo("IT Test Shop");
        // 原生下单没有显式带 order_source，服务端应默认为 native
        assertThat(admin.get("orderSource").asText()).isEqualTo("native");
    }

    @Test
    @Order(17)
    void step17_merchantOrderDetailReturnsMerchantShopAndSource() throws Exception {
        JsonNode merchant = dataOf(getJson("/api/merchant/order/" + firstOrderNo, merchantToken));
        assertThat(merchant.get("merchantName").asText()).isEqualTo("IT Test Merchant");
        assertThat(merchant.get("shopName").asText()).isEqualTo("IT Test Shop");
        assertThat(merchant.get("orderSource").asText()).isEqualTo("native");
    }

    @Test
    @Order(18)
    void step18_adminOrderDetailEpaySource() throws Exception {
        // 易支付进来的订单，详情里 order_source 必须是 epay
        assertThat(epayCallbackOrderNo).isNotBlank();
        JsonNode admin = dataOf(getJson("/api/admin/order/" + epayCallbackOrderNo, adminToken));
        assertThat(admin.get("orderSource").asText()).isEqualTo("epay");
        assertThat(admin.get("merchantName").asText()).isEqualTo("IT Test Merchant");
    }

    @Test
    @Order(19)
    void step19_orderListIncludesOrderSourceOnBothSides() throws Exception {
        // 列表接口本来就是查全字段，这里显式断言 order_source 键存在且非空，别哪天有人手抖砍字段
        JsonNode adminPage = dataOf(getJson("/api/admin/order/page?current=1&size=20", adminToken));
        JsonNode adminRecords = adminPage.get("records");
        assertThat(adminRecords.size()).isGreaterThan(0);
        boolean seenNative = false, seenEpay = false;
        for (int i = 0; i < adminRecords.size(); i++) {
            JsonNode row = adminRecords.get(i);
            assertThat(row.has("orderSource")).as("管理后台列表每条记录都要有 orderSource").isTrue();
            String src = row.get("orderSource").asText();
            assertThat(src).isIn("native", "epay");
            if ("native".equals(src)) seenNative = true;
            if ("epay".equals(src)) seenEpay = true;
        }
        assertThat(seenNative).as("样本里应能看到原生订单").isTrue();
        assertThat(seenEpay).as("样本里应能看到易支付订单").isTrue();

        JsonNode merchantPage = dataOf(getJson("/api/merchant/order/page?current=1&size=20", merchantToken));
        JsonNode merchantRecords = merchantPage.get("records");
        assertThat(merchantRecords.size()).isGreaterThan(0);
        for (int i = 0; i < merchantRecords.size(); i++) {
            assertThat(merchantRecords.get(i).has("orderSource"))
                    .as("商户中心列表每条记录都要有 orderSource").isTrue();
        }
    }

    @Test
    @Order(20)
    void step20_merchantCannotReadOtherMerchantOrder() throws Exception {
        // 场景：商户 A 名下有订单，商户 B 名下没有；B 拿到 A 的订单号来查详情，必须被拒。
        // 这段回归的是 QA 在验收 MTM-157 时发现的越权：改之前详情接口不校验归属，
        // 商户 B 能把 A 的商户名、店铺名、回调返回内容整个读走。
        String otherUsername = "it_merchant_02";
        String otherPassword = "TestPass456";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantName", "IT Test Merchant 2");
        body.put("username", otherUsername);
        body.put("password", otherPassword);
        body.put("contactName", "Tester");
        body.put("contactPhone", "13800000001");
        postJson("/api/admin/merchant", adminToken, body);

        JsonNode login = dataOf(postJson("/api/merchant/login", null,
                Map.of("username", otherUsername, "password", otherPassword)));
        String otherToken = login.get("token").asText();
        assertThat(otherToken).isNotBlank();

        // 拿 B 的令牌查 A 的订单，接口层面必须回错：不放行、不返回订单数据
        MvcResult crossResult = mockMvc.perform(get("/api/merchant/order/" + firstOrderNo)
                        .header("Authorization", "Bearer " + otherToken))
                .andReturn();
        JsonNode crossBody = objectMapper.readTree(crossResult.getResponse().getContentAsByteArray());
        assertThat(crossBody.get("code").asInt()).as("越权访问必须被拒").isNotEqualTo(200);
        assertThat(crossBody.get("message").asText()).contains("无权"); // "无权"
        assertThat(crossBody.has("data") && !crossBody.get("data").isNull() ? crossBody.get("data") : null)
                .as("拒绝时不许把订单数据带出去").isNull();

        // 反向验证：B 查自己名下的订单列表应该是空的（避免因为 B 有订单误判上面的越权检查）
        JsonNode ownPage = dataOf(getJson("/api/merchant/order/page?current=1&size=10", otherToken));
        assertThat(ownPage.get("total").asLong()).isEqualTo(0L);

        // 反向验证：管理员用同一个订单号查，还是能查到（管理员不走归属校验）
        JsonNode adminView = dataOf(getJson("/api/admin/order/" + firstOrderNo, adminToken));
        assertThat(adminView.get("merchantName").asText()).isEqualTo("IT Test Merchant");
    }

    /**
     * 轮询直到 fp_pay_order.notify_count >= expected 或超时（15 秒）。
     * 用于测试等异步回调线程把结果落库。
     */
    private void pollUntilNotifyCountAtLeast(String orderNo, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT notify_count FROM fp_pay_order WHERE order_no = ?")) {
                ps.setString(1, orderNo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt("notify_count") >= expected) {
                        return;
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("超时等待 orderNo=" + orderNo + " 的 notify_count 达到 " + expected);
    }

    @Test
    @Order(8)
    void step8_listPaginationWorks() throws Exception {
        JsonNode merchantPage = dataOf(getJson("/api/admin/merchant/page?current=1&size=1", adminToken));
        assertThat(merchantPage.get("records").size()).isEqualTo(1);
        assertThat(merchantPage.get("total").asLong()).isEqualTo(1L);
        assertThat(merchantPage.get("current").asLong()).isEqualTo(1L);

        JsonNode shopPage = dataOf(getJson("/api/merchant/shop/page?current=1&size=1", merchantToken));
        assertThat(shopPage.get("records").size()).isEqualTo(1);
        assertThat(shopPage.get("total").asLong()).isEqualTo(1L);

        JsonNode qrcodePage = dataOf(getJson("/api/merchant/qrcode/page?current=1&size=1", merchantToken));
        assertThat(qrcodePage.get("records").size()).isEqualTo(1);
        assertThat(qrcodePage.get("total").asLong()).isEqualTo(1L);

        // 到这一步一共下了 2 笔订单（第一笔已支付、第二笔已超时），分页总数应该是 2，每页 1 条应该有 2 页
        JsonNode orderPage = dataOf(getJson("/api/merchant/order/page?current=1&size=1", merchantToken));
        assertThat(orderPage.get("records").size()).isEqualTo(1);
        assertThat(orderPage.get("total").asLong()).isEqualTo(2L);
        assertThat(orderPage.get("pages").asLong()).isEqualTo(2L);
    }

    /**
     * 按 SignUtil.generateSign 的规则现算签名，创建一笔订单，返回平台订单号
     */
    private String createSignedOrder(String outTradeNo, String amount, String subject) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        BigDecimal amountValue = new BigDecimal(amount);

        TreeMap<String, Object> signParams = new TreeMap<>();
        signParams.put("merchantNo", merchantNo);
        signParams.put("outTradeNo", outTradeNo);
        signParams.put("shopNo", shopNo);
        signParams.put("payType", "wxpay");
        signParams.put("amount", amountValue.toPlainString());
        signParams.put("subject", subject);
        signParams.put("timestamp", String.valueOf(timestamp));
        String sign = SignUtil.generateSign(signParams, merchantApiSecret);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantNo", merchantNo);
        body.put("outTradeNo", outTradeNo);
        body.put("shopNo", shopNo);
        body.put("payType", "wxpay");
        body.put("amount", amountValue);
        body.put("subject", subject);
        body.put("timestamp", timestamp);
        body.put("sign", sign);

        JsonNode data = dataOf(postJson("/api/pay/create", null, body));
        String orderNo = data.get("orderNo").asText();
        assertThat(orderNo).isNotBlank();
        return orderNo;
    }

    /**
     * 用 PayNotifyService 自己的签名算法构造一条"微信收款 amount 元"的通知，POST 给回调接口。
     * 回调接口本身不管有没有匹配到订单都返回 code=200，真正是否支付成功要另外查订单状态。
     */
    private void sendWxPayNotify(String amount) throws Exception {
        // PayNotifyServiceImpl 里订单时间有效性判断用的是 createTime.isBefore(notifyTime)，而 receiveTime
        // 这个字段的格式（yyyy-MM-dd HH:mm:ss）只精确到秒。如果下单和这条通知落在同一秒内，订单自带的纳秒级
        // create_time 反而可能比被截断到整秒的 notifyTime 还"晚"，导致误判成"订单还没创建"。这里睡过 1 个整秒
        // 边界，确保 receiveTime 真实地晚于订单创建时间，不是在验业务逻辑，只是让测试不看时序脸色。
        Thread.sleep(1100);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = payNotifyService.generateSign(timestamp, merchantApiSecret);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channelId);
        body.put("packageName", "com.tencent.mm");
        body.put("title", "wechat pay assistant");
        body.put("msg", "[8]wechat pay assistant: " + "微信支付收款" + amount + "元(test)");
        body.put("receiveTime", LocalDateTime.now().format(RECEIVE_TIME_FORMAT));
        body.put("timestamp", timestamp);
        body.put("sign", sign);

        postJson("/api/notify/callback", null, body);
    }

    private MvcResult postJson(String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request)
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
    }

    private MvcResult getJson(String url, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request)
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    /**
     * 用表单形式 POST 到易支付协议入口，不校验响应体的 code 字段（易支付协议返回的是 code=1，不是通用 200）
     */
    private MvcResult postForm(String url, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : params.entrySet()) {
            request = request.param(e.getKey(), e.getValue());
        }
        return mockMvc.perform(request).andReturn();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String k = idx >= 0 ? pair.substring(0, idx) : pair;
            String v = idx >= 0 ? pair.substring(idx + 1) : "";
            out.put(k, URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static class CapturedRequest {
        String method;
        String rawQuery;
    }
}
