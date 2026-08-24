package com.fastpay.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付结果 VO
 *
 * @author xiaomo37564459
 */
@Data
public class PayResultVO {

    /**
     * 平台订单号
     */
    private String orderNo;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 订单金额（商户下单时报的原始金额）
     */
    private BigDecimal amount;

    /**
     * 实际应付金额（在原始 amount 基础上做过 ±0.01~0.99 元微调，避免同金额撞单）。
     * 支付页/前端展示的必须是这个值；对接方需要按这个值付款才能匹配到订单。
     */
    private BigDecimal payAmount;

    /**
     * 支付类型
     */
    private String payType;

    /**
     * 支付方式
     */
    private String payMethod;

    /**
     * 二维码图片URL（API支付时返回）
     */
    private String qrcodeUrl;

    /**
     * 支付页面URL（页面跳转支付时返回）
     */
    private String payPageUrl;

    /**
     * 订单过期时间（ISO-8601 时间字符串，和查询接口 {@code /api/pay/query} 统一）。
     * MTM-176：原本这里是秒级时间戳（Long），跟查询接口的字符串对不上，接入方每次都得写两套解析代码。
     */
    private LocalDateTime expireTime;
}
