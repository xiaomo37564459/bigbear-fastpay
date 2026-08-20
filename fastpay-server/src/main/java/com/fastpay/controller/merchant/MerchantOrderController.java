package com.fastpay.controller.merchant;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fastpay.common.PageResult;
import com.fastpay.common.Result;
import com.fastpay.entity.PayOrder;
import com.fastpay.service.PayOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 商户平台 - 订单管理控制器
 *
 * @author xiaomo37564459
 */
@Tag(name = "商户平台-订单管理", description = "商户订单查询、确认、关闭等操作")
@RestController
@RequestMapping("/api/merchant/order")
public class MerchantOrderController {

    private final PayOrderService payOrderService;

    public MerchantOrderController(PayOrderService payOrderService) {
        this.payOrderService = payOrderService;
    }

    /**
     * 分页查询订单列表
     * <p>
     * 商户前端筛选框传的 orderNo / outTradeNo / subject / payType 必须在这里显式接住，
     * 否则 Spring 会静默丢弃：这就是 MTM-180「搜不到也返回全部订单」的根因。
     */
    @Operation(summary = "分页查询订单", description = "分页查询商户订单列表")
    @GetMapping("/page")
    public Result<PageResult<PayOrder>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String outTradeNo,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String payType,
            @RequestParam(required = false) Integer status,
            HttpServletRequest request) {
        Long merchantId = (Long) request.getAttribute("userId");
        Page<PayOrder> page = payOrderService.pageMerchantOrders(
                new Page<>(current, size), merchantId, shopId,
                orderNo, outTradeNo, subject, payType, status);
        PageResult<PayOrder> result = new PageResult<>(page.getRecords(), page.getTotal(), page.getSize(), page.getCurrent());
        return Result.success(result);
    }

    /**
     * 获取订单详情
     */
    @Operation(summary = "订单详情", description = "根据订单号获取订单详情")
    @GetMapping("/{orderNo}")
    public Result<PayOrder> getByOrderNo(@PathVariable String orderNo, HttpServletRequest request) {
        // 商户端必须带上当前登录商户 ID 做归属校验，别的商户的订单不能查（跟同文件 confirmPay / closeOrder /
        // resendNotify 的做法一致），否则会把商户名、店铺名、回调返回内容这些数据泄露给其他商户。
        Long merchantId = (Long) request.getAttribute("userId");
        PayOrder order = payOrderService.getOrderDetail(orderNo, merchantId);
        return Result.success(order);
    }

    /**
     * 手动确认支付（手动模式）
     */
    @Operation(summary = "确认支付", description = "手动确认订单已支付")
    @PostMapping("/{orderNo}/confirm")
    public Result<Void> confirmPay(@PathVariable String orderNo, HttpServletRequest request) {
        Long merchantId = (Long) request.getAttribute("userId");
        payOrderService.confirmPay(orderNo, merchantId);
        return Result.success("确认成功", null);
    }

    /**
     * 关闭订单
     */
    @Operation(summary = "关闭订单", description = "关闭未支付的订单")
    @PostMapping("/{orderNo}/close")
    public Result<Void> closeOrder(@PathVariable String orderNo, HttpServletRequest request) {
        Long merchantId = (Long) request.getAttribute("userId");
        payOrderService.closeOrder(orderNo, merchantId);
        return Result.success("关闭成功", null);
    }

    /**
     * 重新发送回调通知
     */
    @Operation(summary = "重发通知", description = "重新发送支付回调通知")
    @PostMapping("/{orderNo}/notify")
    public Result<Void> resendNotify(@PathVariable String orderNo, HttpServletRequest request) {
        Long merchantId = (Long) request.getAttribute("userId");
        payOrderService.resendNotify(orderNo, merchantId);
        return Result.success("通知已发送", null);
    }
}
