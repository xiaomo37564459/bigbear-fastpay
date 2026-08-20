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
    private com.fastpay.service.PendingPayAmountService pendingPayAmountService;

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

    // ============================================================
    // 以下 step14 ~ step20 覆盖 MTM-170：pay_amount 微调 + 撞单认对人 + 幂等 + 未匹配落表 +
    // 易支付 money 回传原始 amount + release 契约 + 迁移脚本行为。
    //
    // 排在 MTM-180 的 step20~step27 之后（@Order 30~36），因为 MTM-180 那批"商户订单查询筛选"测试
    // 拿写死的订单数量做断言：本商户下 wxpay=5、UNPAID=2、EPAY 前缀=3 …… 而这里的 step14/15/17/18
    // 会往同一商户塞额外的订单（撞单/未匹配/易支付），如果放前面跑，MTM-180 那几条数量断言会全红。
    //
    // 之前 @Order 用的是 14~20，其中 step20_migrationScript... 还跟 MTM-180 的
    // step20_merchantOrderSearch_orderNoHitsExactlyOne 撞号，本次一并挪开。
    // ============================================================

    @Test
    @Order(30)
    void step14_sameAmountOrdersGetDistinctPayAmounts() throws Exception {
        String orderA = createSignedOrder("IT_C0000001", "5.00", "撞单-A");
        String orderB = createSignedOrder("IT_C0000002", "5.00", "撞单-B");

        BigDecimal payAmountA = readPayAmount(orderA);
        BigDecimal payAmountB = readPayAmount(orderB);

        // 关键：两笔订单同 amount，pay_amount 必须不同
        assertThat(payAmountA).isNotNull();
        assertThat(payAmountB).isNotNull();
        assertThat(payAmountA).isNotEqualByComparingTo(payAmountB);
        // 第一笔应当拿到原始金额
        assertThat(payAmountA).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    @Order(31)
    void step15_notifyMatchesByPayAmountNotByAmount() throws Exception {
        // 场景：A 先下单 5.55 不付、B 后下单 5.55 付款，通知带的是 B 的 pay_amount
        String orderA = createSignedOrder("IT_M0000001", "5.55", "撞单认对人-A");
        String orderB = createSignedOrder("IT_M0000002", "5.55", "撞单认对人-B");
        BigDecimal payAmountA = readPayAmount(orderA);
        BigDecimal payAmountB = readPayAmount(orderB);
        assertThat(payAmountA).isNotEqualByComparingTo(payAmountB);

        // 用 B 的 pay_amount 发通知：老版本会把 A 认成已支付（撞单 bug），修复后必须是 B
        sendWxPayNotify(payAmountB.toPlainString());

        JsonNode aStatus = dataOf(getJson("/api/pay/status/" + orderA, null));
        assertThat(aStatus.get("status").asInt()).isEqualTo(0); // A 依然待支付

        JsonNode bStatus = dataOf(getJson("/api/pay/status/" + orderB, null));
        assertThat(bStatus.get("status").asInt()).isEqualTo(1); // B 已支付
        assertThat(bStatus.get("payAmount").decimalValue()).isEqualByComparingTo(payAmountB);
    }

    @Test
    @Order(32)
    void step16_duplicateNotifyIsIdempotent() throws Exception {
        String orderNo = createSignedOrder("IT_D0000001", "6.66", "重复通知幂等");
        BigDecimal payAmount = readPayAmount(orderNo);

        BigDecimal merchantTotalAmountBefore = readMerchantTotalAmount(merchantId);
        long merchantTotalCountBefore = readMerchantTotalCount(merchantId);

        // 连发两次同金额通知：老版本可能双重累加统计 + 双重回调，修复后必须只算一次
        sendWxPayNotify(payAmount.toPlainString());
        sendWxPayNotify(payAmount.toPlainString());

        JsonNode status = dataOf(getJson("/api/pay/status/" + orderNo, null));
        assertThat(status.get("status").asInt()).isEqualTo(1);

        long merchantTotalCountAfter = readMerchantTotalCount(merchantId);
        BigDecimal merchantTotalAmountAfter = readMerchantTotalAmount(merchantId);
        assertThat(merchantTotalCountAfter - merchantTotalCountBefore).isEqualTo(1);
        assertThat(merchantTotalAmountAfter.subtract(merchantTotalAmountBefore))
                .isEqualByComparingTo(payAmount);
    }

    @Test
    @Order(33)
    void step17_unmatchedNotifyIsRecordedAndVisibleInAdmin() throws Exception {
        long unmatchedBefore = countUnmatchedNotifyByAmount(new BigDecimal("999.99"));

        // 发一笔完全对不上任何订单的金额
        sendWxPayNotify("999.99");

        long unmatchedAfter = countUnmatchedNotifyByAmount(new BigDecimal("999.99"));
        assertThat(unmatchedAfter - unmatchedBefore).isEqualTo(1);

        // 管理后台也能查到这条待处理记录
        JsonNode page = dataOf(getJson(
                "/api/admin/unmatched-notify/page?current=1&size=50&handleStatus=0", adminToken));
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(1L);
        boolean found = false;
        for (JsonNode row : page.get("records")) {
            if (row.get("amount").decimalValue().compareTo(new BigDecimal("999.99")) == 0) {
                found = true;
                assertThat(row.get("handleStatus").asInt()).isEqualTo(0);
                assertThat(row.get("payType").asText()).isEqualTo("wxpay");
                break;
            }
        }
        assertThat(found).as("后台未匹配通知列表应包含刚发的 999.99 元通知").isTrue();
    }

    @Test
    @Order(34)
    void step18_epayCallbackMoneyKeepsOriginalAmount() throws Exception {
        // 起本地 HTTP 服务捕获易支付回调
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

            // A 先下 8.88，占掉 pay_amount=8.88
            Map<String, String> paramsA = new LinkedHashMap<>();
            paramsA.put("pid", merchantNo);
            paramsA.put("out_trade_no", "EPAY_M001A");
            paramsA.put("type", "wxpay");
            paramsA.put("name", "money 测试 A");
            paramsA.put("money", "8.88");
            paramsA.put("notify_url", "http://127.0.0.1:1/no-op-A");
            paramsA.put("sign", EpaySignUtil.generateSign(paramsA, merchantApiSecret));
            paramsA.put("sign_type", "MD5");
            objectMapper.readTree(postForm("/mapi.php", paramsA).getResponse().getContentAsByteArray());

            // B 后下 8.88，pay_amount 会被微调（比如 8.89）
            Map<String, String> paramsB = new LinkedHashMap<>();
            paramsB.put("pid", merchantNo);
            paramsB.put("out_trade_no", "EPAY_M001B");
            paramsB.put("type", "wxpay");
            paramsB.put("name", "money 测试 B");
            paramsB.put("money", "8.88");
            paramsB.put("notify_url", notifyUrl);
            paramsB.put("sign", EpaySignUtil.generateSign(paramsB, merchantApiSecret));
            paramsB.put("sign_type", "MD5");
            JsonNode createB = objectMapper.readTree(
                    postForm("/mapi.php", paramsB).getResponse().getContentAsByteArray());
            String bTradeNo = createB.get("trade_no").asText();

            BigDecimal payAmountB = readPayAmount(bTradeNo);
            assertThat(payAmountB).isNotEqualByComparingTo(new BigDecimal("8.88"));

            // 手动确认 B，触发易支付回调
            postJson("/api/admin/order/" + bTradeNo + "/confirm", adminToken, Map.of());

            CapturedRequest cb = captured.poll(15, TimeUnit.SECONDS);
            assertThat(cb).as("在 15s 内应收到易支付回调").isNotNull();
            Map<String, String> parsed = parseQuery(cb.rawQuery);
            // 关键：money 必须是"原始订单金额" 8.88，不是微调后的 pay_amount
            // sub2api 那边记的订单是 8.88，我们回传 8.89 它会拒收 → 付了钱不给用户充值
            assertThat(parsed.get("money")).isEqualTo("8.88");
            assertThat(parsed.get("trade_status")).isEqualTo("TRADE_SUCCESS");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Order(35)
    void step19_releaseByAmountOnlyDoesNotStealOtherOrdersReservation() throws Exception {
        // 场景：老 release() 只按 (merchant, payType, amount) 三个条件删。并发场景下（比如
        // 一笔已过期订单被两条路径同时触发释放），中间又有一笔新订单刚好抢到了同一金额，
        // 第二次释放会把新订单的占位一起删掉——撞单原样复发。加了 orderNo 保护后就不会。
        String orderA = createSignedOrder("IT_R0000001", "7.77", "release 保护-A");
        BigDecimal payAmountA = readPayAmount(orderA);

        long inTableBefore = countPendingPayAmount(payAmountA);
        assertThat(inTableBefore).as("下单后 A 应该在占位表里").isEqualTo(1L);

        // 用一个别的订单号去释放——因为 order_no 不匹配，什么都不会删
        pendingPayAmountService.release(merchantId, "wxpay", payAmountA, "SOME_OTHER_ORDER_NO");
        long inTableAfterWrongOrderNo = countPendingPayAmount(payAmountA);
        assertThat(inTableAfterWrongOrderNo)
                .as("传别的订单号，占位不该被误删")
                .isEqualTo(1L);

        // 用真正的订单号释放，才会真的删掉
        pendingPayAmountService.release(merchantId, "wxpay", payAmountA, orderA);
        long inTableAfterCorrectOrderNo = countPendingPayAmount(payAmountA);
        assertThat(inTableAfterCorrectOrderNo)
                .as("传本订单号，占位应该被释放")
                .isEqualTo(0L);
    }

    @Test
    @Order(36)
    void step20_migrationScriptCollapsesLegacyUnpaidConflicts() throws Exception {
        // 目的：验证 V1_4 迁移脚本的 4a/4b 两步（原编号 V1_2，MTM-205 顺延）。老库升级时如果有多笔 UNPAID 撞金额，
        // 4a 只保留最早创建的那笔、其它关掉；4b 把剩下的塞进占位表，让新版后端一启动
        // 就能自动避开这些金额。没有这一步，升级窗口内新老订单会再撞一次。
        //
        // 跨库差异：MySQL 4a 的 UPDATE 不能直接 SELECT 同一张表，要嵌套 SELECT 骗过语法限制；
        // PG 无此限制。SQL 由子类各自提供。

        long merchantIdForTest = merchantId;
        Long shopIdForTest = shopId;
        // 构造 3 笔同 pay_amount=12.34 的 legacy UNPAID（创建时间明确错开），
        // 1 笔独占 pay_amount=12.35 的 legacy UNPAID，
        // 1 笔同 pay_amount=12.34 但 status=1 的老订单（不能被误关）
        LocalDateTime future = LocalDateTime.now().plusHours(1);
        String orderOldest = "LEGACY_00001";
        String orderMiddle = "LEGACY_00002";
        String orderNewest = "LEGACY_00003";
        String orderOtherAmount = "LEGACY_00004";
        String orderPaid = "LEGACY_00005";
        try (Connection conn = dataSource.getConnection()) {
            insertLegacyOrder(conn, orderOldest, "LEG_OT_1", merchantIdForTest, shopIdForTest,
                    new BigDecimal("12.34"), new BigDecimal("12.34"), 0, future,
                    LocalDateTime.now().minusMinutes(10));
            insertLegacyOrder(conn, orderMiddle, "LEG_OT_2", merchantIdForTest, shopIdForTest,
                    new BigDecimal("12.34"), new BigDecimal("12.34"), 0, future,
                    LocalDateTime.now().minusMinutes(8));
            insertLegacyOrder(conn, orderNewest, "LEG_OT_3", merchantIdForTest, shopIdForTest,
                    new BigDecimal("12.34"), new BigDecimal("12.34"), 0, future,
                    LocalDateTime.now().minusMinutes(6));
            insertLegacyOrder(conn, orderOtherAmount, "LEG_OT_4", merchantIdForTest, shopIdForTest,
                    new BigDecimal("12.35"), new BigDecimal("12.35"), 0, future,
                    LocalDateTime.now().minusMinutes(5));
            insertLegacyOrder(conn, orderPaid, "LEG_OT_5", merchantIdForTest, shopIdForTest,
                    new BigDecimal("12.34"), new BigDecimal("12.34"), 1, future,
                    LocalDateTime.now().minusMinutes(4));
        }

        // 执行 4a: 关掉冲突老 UNPAID（保留最早的）；4b: 塞进占位表
        try (Connection conn = dataSource.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute(migrationCloseConflictingUnpaidSql());
            }
            try (var st = conn.createStatement()) {
                st.execute(migrationInsertLegacyPendingSql());
            }
        }

        // 断言：
        // 1) orderOldest（最早）依然 UNPAID
        assertThat(readOrderStatus(orderOldest)).isEqualTo(0);
        // 2) middle / newest 被关掉
        assertThat(readOrderStatus(orderMiddle)).isEqualTo(3);
        assertThat(readOrderStatus(orderNewest)).isEqualTo(3);
        // 3) 独占金额的 UNPAID 不受影响
        assertThat(readOrderStatus(orderOtherAmount)).isEqualTo(0);
        // 4) PAID 老订单不受影响
        assertThat(readOrderStatus(orderPaid)).isEqualTo(1);
        // 5) 占位表：应该多出恰好 2 行：orderOldest / orderOtherAmount
        assertThat(countPendingPayAmountByOrderNo(orderOldest)).isEqualTo(1L);
        assertThat(countPendingPayAmountByOrderNo(orderOtherAmount)).isEqualTo(1L);
        // 6) 被关掉的两笔不该出现在占位表里
        assertThat(countPendingPayAmountByOrderNo(orderMiddle)).isEqualTo(0L);
        assertThat(countPendingPayAmountByOrderNo(orderNewest)).isEqualTo(0L);
        // 7) 幂等：脚本再跑一次不会报错
        try (Connection conn = dataSource.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute(migrationInsertLegacyPendingSql());
            }
        }
        assertThat(countPendingPayAmountByOrderNo(orderOldest)).isEqualTo(1L);
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
    @Order(40)
    void step40_successfulNotifyPersistsResponseAndOrderSource() throws Exception {
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
    @Order(41)
    void step41_failedNotifyPersistsErrorMessage() throws Exception {
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
    @Order(42)
    void step42_adminOrderDetailReturnsMerchantShopAndSource() throws Exception {
        // 详情接口口径要跟列表一致，返回 merchantName / shopName / orderSource
        JsonNode admin = dataOf(getJson("/api/admin/order/" + firstOrderNo, adminToken));
        assertThat(admin.get("merchantName").asText()).isEqualTo("IT Test Merchant");
        assertThat(admin.get("shopName").asText()).isEqualTo("IT Test Shop");
        // 原生下单没有显式带 order_source，服务端应默认为 native
        assertThat(admin.get("orderSource").asText()).isEqualTo("native");
    }

    @Test
    @Order(43)
    void step43_merchantOrderDetailReturnsMerchantShopAndSource() throws Exception {
        JsonNode merchant = dataOf(getJson("/api/merchant/order/" + firstOrderNo, merchantToken));
        assertThat(merchant.get("merchantName").asText()).isEqualTo("IT Test Merchant");
        assertThat(merchant.get("shopName").asText()).isEqualTo("IT Test Shop");
        assertThat(merchant.get("orderSource").asText()).isEqualTo("native");
    }

    @Test
    @Order(44)
    void step44_adminOrderDetailEpaySource() throws Exception {
        // 易支付进来的订单，详情里 order_source 必须是 epay
        assertThat(epayCallbackOrderNo).isNotBlank();
        JsonNode admin = dataOf(getJson("/api/admin/order/" + epayCallbackOrderNo, adminToken));
        assertThat(admin.get("orderSource").asText()).isEqualTo("epay");
        assertThat(admin.get("merchantName").asText()).isEqualTo("IT Test Merchant");
    }

    @Test
    @Order(45)
    void step45_orderListIncludesOrderSourceOnBothSides() throws Exception {
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
    @Order(46)
    void step46_merchantCannotReadOtherMerchantOrder() throws Exception {
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

    // MTM-180 起：商户订单列表以前只认 shopId / status，其他筛选参数会被静默丢掉，
    // 前端搜"不存在的订单号"反而拉回全部订单。下面几步锁死每个筛选参数都真的走到查询里。
    // 到 step9~step13 结束后一共 5 笔订单，全部同一商户、全部 wxpay：
    //   IT0000000001（wxpay，已支付，subject=test order 1）
    //   IT0000000002（wxpay，已过期，subject=test order 2）
    //   EPAY0001    （wxpay，待支付，subject=epay 测试商品）
    //   EPAY0002    （wxpay，待支付，subject=submit 测试）
    //   EPAY_CB01   （wxpay，已支付，subject=callback flow）

    @Test
    @Order(20)
    void step20_merchantOrderSearch_orderNoHitsExactlyOne() throws Exception {
        // 拿一个真实的平台订单号搜：结果只能是这一条，total=1
        JsonNode hit = dataOf(getJson("/api/merchant/order/page?orderNo=" + firstOrderNo, merchantToken));
        assertThat(hit.get("total").asLong()).isEqualTo(1L);
        assertThat(hit.get("records").size()).isEqualTo(1);
        assertThat(hit.get("records").get(0).get("orderNo").asText()).isEqualTo(firstOrderNo);
    }

    @Test
    @Order(21)
    void step21_merchantOrderSearch_nonexistentOrderNoReturnsEmpty() throws Exception {
        // 搜一个根本不存在的订单号：必须是空的，不能像修之前那样把全部订单原样返回
        JsonNode miss = dataOf(getJson("/api/merchant/order/page?orderNo=ZZZ-NOT-EXIST-9999", merchantToken));
        assertThat(miss.get("total").asLong()).isZero();
        assertThat(miss.get("records").size()).isZero();
    }

    @Test
    @Order(22)
    void step22_merchantOrderSearch_outTradeNoFilterMatchesMerchantOrderNumber() throws Exception {
        // 商户订单号精准命中：IT0000000001 只匹配第一笔
        JsonNode one = dataOf(getJson("/api/merchant/order/page?outTradeNo=IT0000000001", merchantToken));
        assertThat(one.get("total").asLong()).isEqualTo(1L);
        assertThat(one.get("records").get(0).get("outTradeNo").asText()).isEqualTo("IT0000000001");

        // 商户订单号模糊匹配：EPAY 前缀命中 3 笔
        JsonNode many = dataOf(getJson("/api/merchant/order/page?outTradeNo=EPAY", merchantToken));
        assertThat(many.get("total").asLong()).isEqualTo(3L);

        // 不存在的商户订单号：空
        JsonNode miss = dataOf(getJson("/api/merchant/order/page?outTradeNo=NOT-A-REAL-ONE", merchantToken));
        assertThat(miss.get("total").asLong()).isZero();
    }

    @Test
    @Order(23)
    void step23_merchantOrderSearch_subjectFilterFuzzyMatches() throws Exception {
        // 商品名称模糊：epay 测试商品 只有一笔
        JsonNode one = dataOf(getJson("/api/merchant/order/page?subject=epay", merchantToken));
        assertThat(one.get("total").asLong()).isEqualTo(1L);
        assertThat(one.get("records").get(0).get("subject").asText()).contains("epay");

        // "order" 命中 IT 两笔（test order 1 / test order 2 里都有 order 关键字）
        JsonNode two = dataOf(getJson("/api/merchant/order/page?subject=order", merchantToken));
        assertThat(two.get("total").asLong()).isEqualTo(2L);

        JsonNode miss = dataOf(getJson("/api/merchant/order/page?subject=nothing-here", merchantToken));
        assertThat(miss.get("total").asLong()).isZero();
    }

    @Test
    @Order(24)
    void step24_merchantOrderSearch_payTypeFilter() throws Exception {
        // 5 笔全是 wxpay
        JsonNode wxpay = dataOf(getJson("/api/merchant/order/page?payType=wxpay", merchantToken));
        assertThat(wxpay.get("total").asLong()).isEqualTo(5L);

        // 支付宝 0 笔
        JsonNode alipay = dataOf(getJson("/api/merchant/order/page?payType=alipay", merchantToken));
        assertThat(alipay.get("total").asLong()).isZero();
    }

    @Test
    @Order(25)
    void step25_merchantOrderSearch_statusFilter() throws Exception {
        // 待支付：EPAY0001 + EPAY0002 = 2
        JsonNode unpaid = dataOf(getJson("/api/merchant/order/page?status=0", merchantToken));
        assertThat(unpaid.get("total").asLong()).isEqualTo(2L);

        // 已支付：IT0000000001 + EPAY_CB01 = 2
        JsonNode paid = dataOf(getJson("/api/merchant/order/page?status=1", merchantToken));
        assertThat(paid.get("total").asLong()).isEqualTo(2L);

        // 已过期：IT0000000002 = 1
        JsonNode expired = dataOf(getJson("/api/merchant/order/page?status=2", merchantToken));
        assertThat(expired.get("total").asLong()).isEqualTo(1L);
    }

    @Test
    @Order(26)
    void step26_merchantOrderSearch_multipleFiltersCombineWithAnd() throws Exception {
        // 组合筛选：待支付 + outTradeNo 前缀 EPAY —— 命中 EPAY0001 / EPAY0002 = 2
        JsonNode both = dataOf(getJson(
                "/api/merchant/order/page?status=0&outTradeNo=EPAY", merchantToken));
        assertThat(both.get("total").asLong()).isEqualTo(2L);

        // 组合筛选到无解：已过期 + orderNo 只匹配已支付那笔 -> 空
        JsonNode conflict = dataOf(getJson(
                "/api/merchant/order/page?status=2&orderNo=" + firstOrderNo, merchantToken));
        assertThat(conflict.get("total").asLong()).isZero();
    }

    @Test
    @Order(27)
    void step27_adminOrderSearch_orderNoNonexistentReturnsEmpty() throws Exception {
        // 管理后台的订单列表复用了同一套 pageOrders，顺手锁死一遍：搜不存在的订单号也要空
        JsonNode miss = dataOf(getJson("/api/admin/order/page?orderNo=ZZZ-NOT-EXIST-9999", adminToken));
        assertThat(miss.get("total").asLong()).isZero();
        assertThat(miss.get("records").size()).isZero();

        JsonNode hit = dataOf(getJson("/api/admin/order/page?orderNo=" + firstOrderNo, adminToken));
        assertThat(hit.get("total").asLong()).isEqualTo(1L);
        assertThat(hit.get("records").get(0).get("orderNo").asText()).isEqualTo(firstOrderNo);
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

    /**
     * 读订单当前落库的 pay_amount
     */
    private BigDecimal readPayAmount(String orderNo) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pay_amount FROM fp_pay_order WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBigDecimal("pay_amount");
            }
        }
    }

    /**
     * 读商户累计订单数（用来验证幂等：只累加一次）
     */
    private long readMerchantTotalCount(Long merchantId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT total_count FROM fp_merchant WHERE id = ?")) {
            ps.setLong(1, merchantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong("total_count");
            }
        }
    }

    /**
     * 读商户累计收款金额
     */
    private BigDecimal readMerchantTotalAmount(Long merchantId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT total_amount FROM fp_merchant WHERE id = ?")) {
            ps.setLong(1, merchantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBigDecimal("total_amount");
            }
        }
    }

    /**
     * 迁移脚本 4a 的 SQL 片段：关掉同 (merchant, pay_type, pay_amount) 下多余的 UNPAID 老订单，只保留最早的一笔
     * MySQL / PG 语法不同，由子类各自提供
     */
    protected abstract String migrationCloseConflictingUnpaidSql();

    /**
     * 迁移脚本 4b 的 SQL 片段：把剩下的 UNPAID 老订单塞进占位表
     * MySQL / PG 语法不同，由子类各自提供
     */
    protected abstract String migrationInsertLegacyPendingSql();

    /**
     * 直接往 fp_pay_order 插一笔老数据（绕开下单接口，专门给"迁移脚本能否消解冲突"这类测试用）
     */
    private void insertLegacyOrder(Connection conn, String orderNo, String outTradeNo,
                                    Long merchantIdVal, Long shopIdVal, BigDecimal amount,
                                    BigDecimal payAmount, int status, LocalDateTime expireTime,
                                    LocalDateTime createTime) throws Exception {
        String sql = "INSERT INTO fp_pay_order (order_no, out_trade_no, merchant_id, shop_id, qrcode_id, " +
                "pay_type, pay_method, amount, pay_amount, subject, status, notify_status, notify_count, " +
                "expire_time, order_source, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, NULL, 'wxpay', 'page', ?, ?, ?, ?, 0, 0, ?, 'native', ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderNo);
            ps.setString(2, outTradeNo);
            ps.setLong(3, merchantIdVal);
            ps.setLong(4, shopIdVal);
            ps.setBigDecimal(5, amount);
            ps.setBigDecimal(6, payAmount);
            ps.setString(7, "legacy test");
            ps.setInt(8, status);
            ps.setTimestamp(9, Timestamp.valueOf(expireTime));
            ps.setTimestamp(10, Timestamp.valueOf(createTime));
            ps.setTimestamp(11, Timestamp.valueOf(createTime));
            ps.executeUpdate();
        }
    }

    /**
     * 读订单当前状态
     */
    private int readOrderStatus(String orderNo) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM fp_pay_order WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt("status");
            }
        }
    }

    /**
     * 数 fp_pending_pay_amount 里指定订单号的行数
     */
    private long countPendingPayAmountByOrderNo(String orderNo) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM fp_pending_pay_amount WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 数 fp_pending_pay_amount 里指定 pay_amount 的行数（用来验证 release 契约）
     */
    private long countPendingPayAmount(BigDecimal payAmount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM fp_pending_pay_amount WHERE pay_amount = ?")) {
            ps.setBigDecimal(1, payAmount);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 数 fp_unmatched_notify 里指定金额的行数（用来验证"钱到了但认不到单"被落表了）
     */
    private long countUnmatchedNotifyByAmount(BigDecimal amount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM fp_unmatched_notify WHERE amount = ?")) {
            ps.setBigDecimal(1, amount);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
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
