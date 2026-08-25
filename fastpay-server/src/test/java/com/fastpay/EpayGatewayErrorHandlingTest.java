package com.fastpay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fastpay.common.BusinessException;
import com.fastpay.common.Constants;
import com.fastpay.controller.pay.EpayGatewayController;
import com.fastpay.entity.Merchant;
import com.fastpay.mapper.MerchantMapper;
import com.fastpay.service.PayOrderService;
import com.fastpay.util.EpaySignUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MTM-225：系统内部故障时，易支付协议入口（/mapi.php、/api.php?act=order、/submit.php）
 * 回给对接方的必须是一句稳定的、能看懂的中文提示，不能是 null、不能是空串、不能泄漏
 * 堆栈 / 类名 / SQL / 数据库地址。
 *
 * 同时兜底保证 MTM-212 已修的「参数缺失」类具体中文提示保持不变。
 *
 * 这里用 MockMvc 的 standaloneSetup + Mockito，直接把 controller 拎出来测，不启动
 * 完整 Spring 容器，也不需要真的数据库。跑得快，也不受主流程集成测的执行顺序影响。
 */
@ExtendWith(MockitoExtension.class)
class EpayGatewayErrorHandlingTest {

    private static final String MERCHANT_NO = "M20260824TEST";
    private static final String API_SECRET = "test-api-secret-please-ignore-1234567890";
    /** 通用兜底提示：所有非业务/校验类的内部故障都必须原样返回这句话 */
    private static final String GENERIC_INTERNAL_MSG = "服务暂时不可用，请稍后重试";
    /** 模拟数据库连不上时 Spring 抛出的典型消息，故意带 JDBC URL / mysql / 类名，
     *  用来验证我们绝对不会把它透出去 */
    private static final String LEAKY_JDBC_MESSAGE =
            "Failed to obtain JDBC Connection: com.mysql.cj.jdbc.exceptions.CommunicationsException: "
                    + "Communications link failure. jdbc:mysql://10.0.0.5:3306/bigbear_fastpay "
                    + "at java.base/sun.nio.ch.SocketChannelImpl.connect(SocketChannelImpl.java:99)";

    @Mock
    private PayOrderService payOrderService;

    @Mock
    private MerchantMapper merchantMapper;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        EpayGatewayController controller = new EpayGatewayController(payOrderService, merchantMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ============================================================
    // /mapi.php
    // ============================================================

    @Test
    void mapi_whenMerchantLookupThrowsDatabaseError_returnsStableGenericMessage() throws Exception {
        // 数据库连不上：mapper.selectOne 抛 DataAccessResourceFailureException
        when(merchantMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenThrow(new DataAccessResourceFailureException(LEAKY_JDBC_MESSAGE));

        JsonNode resp = doMapi(validMapiParams(), true);
        assertGenericInternalMessage(resp);
        assertNoInternalLeaks(resp.toString());
    }

    @Test
    void mapi_whenServiceThrowsRuntimeExceptionWithLeakyMessage_returnsStableGenericMessage()
            throws Exception {
        // 商户能查到，签名对，但服务层抛内部异常（消息里带类名/英文）
        givenMerchantEnabled();
        when(payOrderService.createEpayOrder(any(), anyString()))
                .thenThrow(new RuntimeException(
                        "java.lang.NullPointerException at com.fastpay.service.PayOrderServiceImpl.createEpayOrder"));

        JsonNode resp = doMapi(validMapiParams(), true);
        assertGenericInternalMessage(resp);
        assertNoInternalLeaks(resp.toString());
    }

    @Test
    void mapi_whenServiceThrowsNullMessageException_returnsStableGenericMessage() throws Exception {
        // 最经典的 bug 场景：异常 getMessage() 返回 null（NPE 常见），
        // 修之前会返回 {"code":-1,"msg":null}
        givenMerchantEnabled();
        when(payOrderService.createEpayOrder(any(), anyString()))
                .thenThrow(new NullPointerException());

        JsonNode resp = doMapi(validMapiParams(), true);
        assertGenericInternalMessage(resp);
        assertThat(resp.get("msg").isNull()).as("msg 绝对不能是 null").isFalse();
    }

    @Test
    void mapi_whenPidMissing_stillReturnsMtm212SpecificMessage() throws Exception {
        // 回归兜底：MTM-212 已修的「缺少商户号 pid」这类具体提示不能被 MTM-225 打回原形
        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "IT_MTM225_01");
        params.put("type", "wxpay");
        params.put("money", "1.00");
        params.put("name", "no pid");
        // 故意不带 pid，也不签名

        JsonNode resp = doMapi(params, false);
        assertThat(resp.get("code").asInt()).isEqualTo(-1);
        assertThat(resp.get("msg").asText()).isEqualTo("缺少商户号 pid");
    }

    @Test
    void mapi_whenBusinessRuleRejects_returnsBusinessMessage() throws Exception {
        // 服务层 BusinessException 抛的都是明确的中文业务提示，
        // 这类是「已被产品化的用户可见文案」，兜底不能把它盖成通用提示
        givenMerchantEnabled();
        when(payOrderService.createEpayOrder(any(), anyString()))
                .thenThrow(new BusinessException("暂无可用的收款通道，请联系商户"));

        JsonNode resp = doMapi(validMapiParams(), true);
        assertThat(resp.get("code").asInt()).isEqualTo(-1);
        assertThat(resp.get("msg").asText()).isEqualTo("暂无可用的收款通道，请联系商户");
    }

    // ============================================================
    // /api.php?act=order
    // ============================================================

    @Test
    void apiOrder_whenMerchantLookupThrowsDatabaseError_returnsStableGenericMessage() throws Exception {
        when(merchantMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenThrow(new DataAccessResourceFailureException(LEAKY_JDBC_MESSAGE));

        MockHttpServletRequestBuilder req = get("/api.php")
                .param("act", "order")
                .param("pid", MERCHANT_NO)
                .param("out_trade_no", "IT_MTM225_Q1")
                .param("sign", "dummy")
                .param("sign_type", "MD5");
        MvcResult r = mockMvc.perform(req).andReturn();
        JsonNode resp = objectMapper.readTree(r.getResponse().getContentAsByteArray());

        assertGenericInternalMessage(resp);
        assertNoInternalLeaks(resp.toString());
    }

    @Test
    void apiOrder_whenServiceQueryThrowsDatabaseError_returnsStableGenericMessage() throws Exception {
        // 商户能查到、签名对，但真查订单时数据库炸了
        givenMerchantEnabled();
        when(payOrderService.queryOrderByOutTradeNo(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException(LEAKY_JDBC_MESSAGE));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", MERCHANT_NO);
        params.put("out_trade_no", "IT_MTM225_Q2");
        String sign = EpaySignUtil.generateSign(params, API_SECRET);
        MockHttpServletRequestBuilder req = get("/api.php")
                .param("act", "order")
                .param("pid", MERCHANT_NO)
                .param("out_trade_no", "IT_MTM225_Q2")
                .param("sign", sign)
                .param("sign_type", "MD5");
        MvcResult r = mockMvc.perform(req).andReturn();
        JsonNode resp = objectMapper.readTree(r.getResponse().getContentAsByteArray());

        assertGenericInternalMessage(resp);
        assertNoInternalLeaks(resp.toString());
    }

    // ============================================================
    // /submit.php
    // ============================================================

    @Test
    void submit_whenMerchantLookupThrowsDatabaseError_bodyHasGenericMessageOnly() throws Exception {
        when(merchantMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenThrow(new DataAccessResourceFailureException(LEAKY_JDBC_MESSAGE));

        Map<String, String> params = validMapiParams();
        MockHttpServletRequestBuilder req = post("/submit.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : params.entrySet()) {
            req = req.param(e.getKey(), e.getValue());
        }
        MvcResult r = mockMvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body).contains(GENERIC_INTERNAL_MSG);
        assertThat(body).doesNotContain("null");
        assertNoInternalLeaks(body);
    }

    @Test
    void submit_whenPidMissing_stillReturnsMtm212SpecificMessage() throws Exception {
        MockHttpServletRequestBuilder req = post("/submit.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", "IT_MTM225_S1")
                .param("type", "wxpay")
                .param("money", "1.00")
                .param("name", "no pid");
        MvcResult r = mockMvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("缺少商户号 pid");
    }

    // ============================================================
    // helpers
    // ============================================================

    private JsonNode doMapi(Map<String, String> params, boolean sign) throws Exception {
        Map<String, String> effective = new LinkedHashMap<>(params);
        if (sign) {
            effective.put("sign", EpaySignUtil.generateSign(effective, API_SECRET));
            effective.put("sign_type", "MD5");
        }
        MockHttpServletRequestBuilder req = post("/mapi.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (Map.Entry<String, String> e : effective.entrySet()) {
            req = req.param(e.getKey(), e.getValue());
        }
        MvcResult r = mockMvc.perform(req).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsByteArray());
    }

    private Map<String, String> validMapiParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", MERCHANT_NO);
        params.put("out_trade_no", "IT_MTM225_M1");
        params.put("type", "wxpay");
        params.put("name", "MTM-225 兜底提示测试");
        params.put("money", "1.23");
        params.put("notify_url", "http://127.0.0.1:1/no-op");
        return params;
    }

    private void givenMerchantEnabled() {
        Merchant m = new Merchant();
        m.setMerchantNo(MERCHANT_NO);
        m.setApiSecret(API_SECRET);
        m.setStatus(Constants.Status.ENABLED);
        when(merchantMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(m);
    }

    private void assertGenericInternalMessage(JsonNode resp) {
        assertThat(resp.get("code").asInt()).isEqualTo(-1);
        assertThat(resp.get("msg").isNull()).as("msg 不能是 null").isFalse();
        String msg = resp.get("msg").asText();
        assertThat(msg).as("msg 不能是空串").isNotBlank();
        assertThat(msg).as("msg 必须是稳定的通用提示").isEqualTo(GENERIC_INTERNAL_MSG);
    }

    /**
     * 兜底断言：整个响应里不能出现任何内部实现细节的痕迹。
     * 只覆盖典型泄漏关键字，不追求"绝对"——真出现新形式的泄漏，测试自然会漏，
     * 但至少能挡住最常见的 stack / JDBC / 类名 / SQL 状态位。
     */
    private void assertNoInternalLeaks(String body) {
        String lower = body.toLowerCase();
        assertThat(lower)
                .as("响应体不能含有 JDBC / 数据库 / 类名 / 堆栈 关键字：body=%s", body)
                .doesNotContain("jdbc")
                .doesNotContain("mysql")
                .doesNotContain("postgres")
                .doesNotContain("sqlexception")
                .doesNotContain("nullpointer")
                .doesNotContain("dataaccess")
                .doesNotContain("com.fastpay")
                .doesNotContain("com.mysql")
                .doesNotContain("java.lang.")
                .doesNotContain("at java")
                .doesNotContain("communications link");
    }
}
