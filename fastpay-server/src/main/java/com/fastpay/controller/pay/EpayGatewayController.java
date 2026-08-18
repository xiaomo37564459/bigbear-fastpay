package com.fastpay.controller.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fastpay.common.Constants;
import com.fastpay.dto.EpayCreateOrderDTO;
import com.fastpay.entity.Merchant;
import com.fastpay.entity.PayOrder;
import com.fastpay.mapper.MerchantMapper;
import com.fastpay.service.PayOrderService;
import com.fastpay.util.EpaySignUtil;
import com.fastpay.vo.PayResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 彩虹易支付协议入口控制器
 *
 * 只做「翻译层」：入站的彩虹易支付格式（pid / type / out_trade_no / money ...）
 * 翻译成现有 FastPay 的下单流程；回调时按订单来源（{@link Constants.OrderSource}）
 * 反向翻译回易支付格式。
 *
 * 三个入口：
 * - GET/POST /submit.php  用户扫码前的页面跳转入口
 * - POST     /mapi.php    API 下单入口，返回 JSON
 * - GET      /api.php?act=order  查询订单状态
 *
 * 路径解释：Spring Boot 里 server.servlet.context-path=/fastpay-server，
 * 所以实际外部地址是 /fastpay-server/submit.php 等。sub2api 后台里可以直接
 * 填这个带前缀的完整地址；如果对方不认前缀，走 nginx rewrite（见 README）。
 */
@Slf4j
@Tag(name = "彩虹易支付协议入口", description = "兼容彩虹易支付通用协议，供 sub2api 等第三方系统对接")
@RestController
@RequestMapping
public class EpayGatewayController {

    private final PayOrderService payOrderService;
    private final MerchantMapper merchantMapper;

    public EpayGatewayController(PayOrderService payOrderService, MerchantMapper merchantMapper) {
        this.payOrderService = payOrderService;
        this.merchantMapper = merchantMapper;
    }

    /**
     * 页面跳转支付：验签、下单，然后 302 到平台支付页
     */
    @Operation(summary = "易支付-页面跳转支付", description = "彩虹易支付通用协议 /submit.php 入口")
    @RequestMapping(value = "/submit.php", method = {org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST})
    public void submit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> params = collectParams(request);
        log.info("易支付 /submit.php 收到请求: params={}", params);

        try {
            EpayCreateOrderDTO dto = verifyAndBuildDto(params);
            PayResultVO vo = payOrderService.createEpayOrder(dto, getClientIp(request));
            response.sendRedirect(vo.getPayPageUrl());
        } catch (Exception e) {
            log.warn("易支付 /submit.php 处理失败: msg={}", e.getMessage());
            response.setContentType("text/plain;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("下单失败：" + e.getMessage());
        }
    }

    /**
     * API 下单：返回 JSON。彩虹易支付协议里 code=1 表示成功
     */
    @Operation(summary = "易支付-API下单", description = "彩虹易支付通用协议 /mapi.php 入口")
    @PostMapping("/mapi.php")
    @ResponseBody
    public Map<String, Object> mapi(HttpServletRequest request) {
        Map<String, String> params = collectParams(request);
        log.info("易支付 /mapi.php 收到请求: params={}", params);

        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            EpayCreateOrderDTO dto = verifyAndBuildDto(params);
            PayResultVO vo = payOrderService.createEpayOrder(dto, getClientIp(request));

            resp.put("code", 1);
            resp.put("msg", "success");
            resp.put("trade_no", vo.getOrderNo());
            resp.put("payurl", vo.getPayPageUrl());
            // 收款码原始串（微信/支付宝二维码里解码出来的 URL），
            // 部分 sub2api 版本会拿它自己渲染二维码
            if (StringUtils.hasText(vo.getQrcodeUrl())) {
                resp.put("qrcode", vo.getQrcodeUrl());
            }
        } catch (Exception e) {
            log.warn("易支付 /mapi.php 处理失败: msg={}", e.getMessage());
            resp.put("code", -1);
            resp.put("msg", e.getMessage());
        }
        return resp;
    }

    /**
     * 查询订单状态。sub2api 有时候会用来兜底轮询
     * 彩虹易支付协议约定：code=1 且 status=1 表示已支付
     */
    @Operation(summary = "易支付-订单查询", description = "彩虹易支付通用协议 /api.php?act=order 入口")
    @GetMapping("/api.php")
    @ResponseBody
    public Map<String, Object> api(HttpServletRequest request) {
        Map<String, String> params = collectParams(request);
        String act = params.get("act");
        Map<String, Object> resp = new LinkedHashMap<>();

        if (!"order".equals(act)) {
            resp.put("code", -1);
            resp.put("msg", "不支持的 act：" + act);
            return resp;
        }

        try {
            Merchant merchant = loadMerchant(params.get("pid"));
            // 查询接口签名不参与 act（act 只是路由字段），另外常见对接方也会用
            // 直接传 key=api_secret 代替签名，两种模式都放行
            String key = params.remove("key");
            Map<String, String> forSign = new LinkedHashMap<>(params);
            forSign.remove("act");
            boolean signOk = EpaySignUtil.verifySign(forSign, merchant.getApiSecret());
            boolean keyOk = key != null && key.equals(merchant.getApiSecret());
            if (!signOk && !keyOk) {
                throw new IllegalArgumentException("签名验证失败");
            }

            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            PayOrder order = null;
            if (StringUtils.hasText(outTradeNo)) {
                order = payOrderService.queryOrderByOutTradeNo(merchant.getMerchantNo(), outTradeNo);
            } else if (StringUtils.hasText(tradeNo)) {
                order = payOrderService.queryOrder(tradeNo);
            }
            if (order == null) {
                resp.put("code", -1);
                resp.put("msg", "订单不存在");
                return resp;
            }

            resp.put("code", 1);
            resp.put("msg", "success");
            resp.put("trade_no", order.getOrderNo());
            resp.put("out_trade_no", order.getOutTradeNo());
            resp.put("type", order.getPayType());
            resp.put("pid", merchant.getMerchantNo());
            resp.put("addtime", order.getCreateTime() != null ? order.getCreateTime().toString() : "");
            resp.put("endtime", order.getPayTime() != null ? order.getPayTime().toString() : "");
            resp.put("name", order.getSubject());
            resp.put("money", order.getAmount().toPlainString());
            resp.put("status", Constants.OrderStatus.PAID.equals(order.getStatus()) ? 1 : 0);
        } catch (Exception e) {
            log.warn("易支付 /api.php?act=order 处理失败: msg={}", e.getMessage());
            resp.put("code", -1);
            resp.put("msg", e.getMessage());
        }
        return resp;
    }

    // ---- 内部工具 ----

    /**
     * 把 servlet 参数拍平成 Map。同名多值只保留第一个，够用了 —— 彩虹易支付协议里没有数组参数
     */
    private Map<String, String> collectParams(HttpServletRequest request) {
        Map<String, String> params = new TreeMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            if (values != null && values.length > 0) {
                params.put(entry.getKey(), values[0]);
            }
        }
        return params;
    }

    private Merchant loadMerchant(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new IllegalArgumentException("缺少商户号 pid");
        }
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getMerchantNo, pid));
        if (merchant == null) {
            throw new IllegalArgumentException("商户不存在：" + pid);
        }
        if (!Constants.Status.ENABLED.equals(merchant.getStatus())) {
            throw new IllegalArgumentException("商户已被禁用");
        }
        return merchant;
    }

    /**
     * 走 /submit.php 和 /mapi.php 的公共前置：查商户 → 校验签名 → 装成服务层 DTO
     */
    private EpayCreateOrderDTO verifyAndBuildDto(Map<String, String> params) {
        Merchant merchant = loadMerchant(params.get("pid"));

        if (!EpaySignUtil.verifySign(params, merchant.getApiSecret())) {
            throw new IllegalArgumentException("签名验证失败");
        }

        String outTradeNo = params.get("out_trade_no");
        String type = params.get("type");
        String money = params.get("money");
        String name = params.get("name");
        if (!StringUtils.hasText(outTradeNo)) {
            throw new IllegalArgumentException("缺少 out_trade_no");
        }
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("缺少 type");
        }
        if (!StringUtils.hasText(money)) {
            throw new IllegalArgumentException("缺少 money");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("缺少 name");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(money);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("money 不是合法金额：" + money);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }

        EpayCreateOrderDTO dto = new EpayCreateOrderDTO();
        dto.setMerchantNo(merchant.getMerchantNo());
        dto.setOutTradeNo(outTradeNo);
        dto.setPayType(type);
        dto.setAmount(amount);
        dto.setSubject(name);
        dto.setNotifyUrl(params.get("notify_url"));
        dto.setReturnUrl(params.get("return_url"));
        return dto;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

}
