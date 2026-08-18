package com.fastpay.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 彩虹易支付协议下单入参
 *
 * 上游控制器已完成 pid → 商户查找 和 sign 校验，把可信信息装到这个 DTO
 * 里传给服务层。字段一律用彩虹易支付协议的原名，方便和抓包对照。
 */
@Data
public class EpayCreateOrderDTO {

    /** 商户号（对应 pid，实际就是 fp_merchant.merchant_no） */
    private String merchantNo;

    /** 商户订单号（对应 out_trade_no） */
    private String outTradeNo;

    /** 支付方式（对应 type：wxpay / alipay） */
    private String payType;

    /** 订单金额（对应 money，单位：元） */
    private BigDecimal amount;

    /** 商品名称（对应 name） */
    private String subject;

    /** 异步回调地址（对应 notify_url） */
    private String notifyUrl;

    /** 同步跳转地址（对应 return_url，可为空） */
    private String returnUrl;
}
