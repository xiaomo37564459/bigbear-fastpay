package com.fastpay.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fastpay.dto.CreateOrderDTO;
import com.fastpay.dto.EpayCreateOrderDTO;
import com.fastpay.entity.PayOrder;
import com.fastpay.vo.PayResultVO;

/**
 * 支付订单服务接口
 *
 * @author FastPay
 */
public interface PayOrderService extends IService<PayOrder> {

    /**
     * 创建支付订单
     *
     * @param dto      订单信息
     * @param clientIp 客户端IP
     * @return 支付结果
     */
    PayResultVO createOrder(CreateOrderDTO dto, String clientIp);

    /**
     * 通过彩虹易支付协议入口创建订单。
     * 上游控制器已经完成商户查找和易支付签名校验，服务层跳过 sign 校验，
     * 使用请求携带的 notify_url / return_url（不用商户默认值），
     * order_source 固定为 {@link com.fastpay.common.Constants.OrderSource#EPAY}。
     *
     * @param dto      易支付协议入参
     * @param clientIp 客户端IP
     * @return 平台订单号 + 支付页/二维码地址
     */
    PayResultVO createEpayOrder(EpayCreateOrderDTO dto, String clientIp);

    /**
     * 查询订单状态
     *
     * @param orderNo 平台订单号
     * @return 订单信息
     */
    PayOrder queryOrder(String orderNo);

    /**
     * 获取订单详情（供 admin/merchant 详情接口使用），
     * 在 {@link #queryOrder(String)} 的基础上补齐 merchantName / shopName，
     * 保证详情接口和列表接口的返回口径一致（前端不用再从列表带名字过来顶）。
     *
     * @param orderNo 平台订单号
     * @return 订单信息（含商户名、店铺名），未命中返回 null
     */
    PayOrder getOrderDetail(String orderNo);

    /**
     * 查询订单状态（通过商户订单号）
     *
     * @param merchantNo  商户编号
     * @param outTradeNo  商户订单号
     * @return 订单信息
     */
    PayOrder queryOrderByOutTradeNo(String merchantNo, String outTradeNo);

    /**
     * 手动确认支付
     *
     * @param orderNo    平台订单号
     * @param merchantId 商户ID
     */
    void confirmPay(String orderNo, Long merchantId);

    /**
     * 关闭订单
     *
     * @param orderNo    平台订单号
     * @param merchantId 商户ID
     */
    void closeOrder(String orderNo, Long merchantId);

    /**
     * 分页查询订单列表（管理员）
     *
     * @param page       分页参数
     * @param merchantId 商户ID
     * @param shopId     店铺ID
     * @param orderNo    订单号
     * @param status     状态
     * @return 分页结果
     */
    Page<PayOrder> pageOrders(Page<PayOrder> page, Long merchantId, Long shopId, String orderNo, Integer status);

    /**
     * 分页查询商户订单列表
     *
     * @param page       分页参数
     * @param merchantId 商户ID
     * @param shopId     店铺ID
     * @param status     状态
     * @return 分页结果
     */
    Page<PayOrder> pageMerchantOrders(Page<PayOrder> page, Long merchantId, Long shopId, Integer status);

    /**
     * 获取支付页面数据
     *
     * @param orderNo 平台订单号
     * @return 订单信息（包含二维码）
     */
    PayOrder getPayPageData(String orderNo);

    /**
     * 处理过期订单
     */
    void processExpiredOrders();

    /**
     * 发送回调通知
     *
     * @param orderNo
     */
    void sendNotify(String orderNo);

    /**
     * 重新发送回调通知（带权限校验）
     *
     * @param orderNo    平台订单号
     * @param merchantId 商户ID（用于权限校验）
     */
    void resendNotify(String orderNo, Long merchantId);

    /**
     * 处理需要重发的回调通知
     * 自动重发失败的回调通知，最多重试5次
     */
    void processFailedNotify();

    /**
     * 更新订单的支付成功跳转地址
     *
     * @param orderNo   平台订单号
     * @param returnUrl 支付成功跳转地址
     */
    void updateReturnUrl(String orderNo, String returnUrl);
}
