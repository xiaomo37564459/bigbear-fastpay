package com.fastpay.vo;

import lombok.Data;

import java.math.BigDecimal;

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
     * 订单过期时间（时间戳，秒）
     */
    private Long expireTime;
}
