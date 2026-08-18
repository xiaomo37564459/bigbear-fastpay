package com.fastpay;

import com.fastpay.service.PayNotifyService;
import com.fastpay.service.PayOrderService;
import com.fastpay.util.SignUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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

    @Test
    @Order(1)
    void step1_adminCanLoginWithSeedAccount() throws Exception {
        // db/init.sql 和 db/init-pg.sql 里都预置了 admin/123456 这个管理员账号
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
}
