package com.fastpay.service.impl;

import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fastpay.common.BusinessException;
import com.fastpay.common.Constants;
import com.fastpay.dto.CreateOrderDTO;
import com.fastpay.dto.EpayCreateOrderDTO;
import com.fastpay.entity.Merchant;
import com.fastpay.entity.PayOrder;
import com.fastpay.entity.PayQrcode;
import com.fastpay.entity.Shop;
import com.fastpay.mapper.MerchantMapper;
import com.fastpay.mapper.PayOrderMapper;
import com.fastpay.mapper.PayQrcodeMapper;
import com.fastpay.mapper.ShopMapper;
import com.fastpay.service.PayOrderService;
import com.fastpay.service.PayQrcodeService;
import com.fastpay.service.PendingPayAmountService;
import com.fastpay.util.EpaySignUtil;
import com.fastpay.util.SignUtil;
import com.fastpay.vo.PayResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * 支付订单服务实现类
 *
 * @author xiaomo37564459
 */
@Slf4j
@Service
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements PayOrderService {

    private final MerchantMapper merchantMapper;
    private final ShopMapper shopMapper;
    private final PayQrcodeMapper payQrcodeMapper;
    private final PayQrcodeService payQrcodeService;
    private final PendingPayAmountService pendingPayAmountService;
    private final Executor payNotifyExecutor;

    @Value("${fastpay.pay.order-timeout-minutes}")
    private Integer orderTimeoutMinutes;

    @Value("${fastpay.pay.page-domain}")
    private String pageDomain;

    public PayOrderServiceImpl(MerchantMapper merchantMapper, ShopMapper shopMapper,
                               PayQrcodeMapper payQrcodeMapper, PayQrcodeService payQrcodeService,
                               PendingPayAmountService pendingPayAmountService,
                               @Qualifier("payNotifyExecutor") Executor payNotifyExecutor) {
        this.merchantMapper = merchantMapper;
        this.shopMapper = shopMapper;
        this.payQrcodeMapper = payQrcodeMapper;
        this.payQrcodeService = payQrcodeService;
        this.pendingPayAmountService = pendingPayAmountService;
        this.payNotifyExecutor = payNotifyExecutor;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO createOrder(CreateOrderDTO dto, String clientIp) {
        // 验证商户
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getMerchantNo, dto.getMerchantNo()));
        if (merchant == null) {
            throw new BusinessException("商户不存在");
        }
        if (!Constants.Status.ENABLED.equals(merchant.getStatus())) {
            throw new BusinessException("商户已被禁用");
        }

        // 验证签名
        TreeMap<String, Object> signParams = new TreeMap<>();
        signParams.put("merchantNo", dto.getMerchantNo());
        signParams.put("outTradeNo", dto.getOutTradeNo());
        signParams.put("shopNo", dto.getShopNo());
        signParams.put("payType", dto.getPayType());
        signParams.put("amount", dto.getAmount().toPlainString());
        signParams.put("subject", dto.getSubject());
        signParams.put("timestamp", String.valueOf(dto.getTimestamp()));
        if (StringUtils.hasText(dto.getReturnUrl())) {
            signParams.put("returnUrl", dto.getReturnUrl());
        }
        if (StringUtils.hasText(dto.getExtParam())) {
            signParams.put("extParam", dto.getExtParam());
        }
        signParams.put("sign", dto.getSign());

        if (!SignUtil.verifySign(signParams, merchant.getApiSecret())) {
            throw new BusinessException("签名验证失败");
        }

        // 验证时间戳（5分钟内有效）
        long currentTime = System.currentTimeMillis() / 1000;
        if (Math.abs(currentTime - dto.getTimestamp()) > 300) {
            throw new BusinessException("请求已过期");
        }

        // 检查订单号是否重复
        Long existCount = this.count(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getMerchantId, merchant.getId())
                .eq(PayOrder::getOutTradeNo, dto.getOutTradeNo()));
        if (existCount > 0) {
            throw new BusinessException("商户订单号已存在");
        }

        // 获取可用的收款二维码
        PayQrcode qrcode = payQrcodeService.getAvailableQrcode(merchant.getId(),dto.getShopNo(), dto.getPayType());
        if (qrcode == null) {
            throw new BusinessException("暂无可用的收款通道，请联系商户");
        }

        // 获取店铺信息
        Shop shop = shopMapper.selectById(qrcode.getShopId());

        // 创建订单
        PayOrder order = new PayOrder();
        order.setOrderNo(SignUtil.generateOrderNo());
        order.setOutTradeNo(dto.getOutTradeNo());
        order.setMerchantId(merchant.getId());
        order.setShopId(qrcode.getShopId());
        order.setShopName(shop.getShopName());
        order.setShopNo(shop.getShopNo());
        order.setQrcodeId(qrcode.getId());
        order.setPayType(qrcode.getPayType());
        order.setPayMethod(StringUtils.hasText(dto.getPayMethod()) ? dto.getPayMethod() : Constants.PayMethod.PAGE);
        order.setAmount(dto.getAmount());
        order.setSubject(dto.getSubject());
        order.setStatus(Constants.OrderStatus.UNPAID);
        order.setNotifyUrl(merchant.getNotifyUrl());
        order.setReturnUrl(merchant.getReturnUrl());
        order.setNotifyStatus(0);
        order.setNotifyCount(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(orderTimeoutMinutes));
        order.setClientIp(clientIp);
        order.setExtParam(dto.getExtParam());
        order.setOrderSource(Constants.OrderSource.NATIVE);

        // 分配唯一 pay_amount（在原始 amount 附近微调），彻底避免"两人同价撞单认错人"
        BigDecimal payAmount = pendingPayAmountService.allocate(
                merchant.getId(), qrcode.getPayType(), dto.getAmount(),
                order.getOrderNo(), order.getExpireTime());
        order.setPayAmount(payAmount);

        try {
            this.save(order);
        } catch (RuntimeException e) {
            // 订单落库失败要把 pay_amount 占位释放掉，否则会长时间占着一个金额白白挡后来的订单
            pendingPayAmountService.release(merchant.getId(), qrcode.getPayType(), payAmount, order.getOrderNo());
            throw e;
        }
        log.info("创建支付订单成功: orderNo={}, amount={}, payAmount={}",
                order.getOrderNo(), order.getAmount(), order.getPayAmount());

        // 构建返回结果
        PayResultVO vo = new PayResultVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOutTradeNo(order.getOutTradeNo());
        vo.setAmount(order.getAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setPayType(order.getPayType());
        vo.setPayMethod(order.getPayMethod());
        // MTM-176：expireTime 直接把 LocalDateTime 交给 Jackson，序列化成 ISO-8601 字符串，
        // 和 /api/pay/query、/api/pay/page 保持同一种格式，避免接入方为一个字段写两套解析代码
        vo.setExpireTime(order.getExpireTime());

        if (Constants.PayMethod.API.equals(order.getPayMethod())) {
            // API支付，返回二维码URL
            vo.setQrcodeUrl(qrcode.getQrcodeUrl());
        } else {
            // 页面跳转支付，跳转到商户前端支付页面
            vo.setPayPageUrl(pageDomain + "/pay/" + order.getOrderNo());
        }

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO createEpayOrder(EpayCreateOrderDTO dto, String clientIp) {
        // 商户已在控制器里查过存在且启用，这里再取一次拿到 id 等字段。做重复校验是防御，成本极低
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getMerchantNo, dto.getMerchantNo()));
        if (merchant == null) {
            throw new BusinessException("商户不存在");
        }
        if (!Constants.Status.ENABLED.equals(merchant.getStatus())) {
            throw new BusinessException("商户已被禁用");
        }

        // 订单号去重
        Long existCount = this.count(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getMerchantId, merchant.getId())
                .eq(PayOrder::getOutTradeNo, dto.getOutTradeNo()));
        if (existCount > 0) {
            throw new BusinessException("商户订单号已存在");
        }

        // 易支付协议不带 shopNo，跨店铺挑一张可用的二维码
        PayQrcode qrcode = payQrcodeService.getAvailableQrcodeAnyShop(merchant.getId(), dto.getPayType());
        if (qrcode == null) {
            throw new BusinessException("暂无可用的收款通道，请联系商户");
        }

        Shop shop = shopMapper.selectById(qrcode.getShopId());

        PayOrder order = new PayOrder();
        order.setOrderNo(SignUtil.generateOrderNo());
        order.setOutTradeNo(dto.getOutTradeNo());
        order.setMerchantId(merchant.getId());
        order.setShopId(qrcode.getShopId());
        if (shop != null) {
            order.setShopName(shop.getShopName());
            order.setShopNo(shop.getShopNo());
        }
        order.setQrcodeId(qrcode.getId());
        order.setPayType(qrcode.getPayType());
        // 易支付协议下 sub2api 会自己跳转到 payurl，走的是页面模式
        order.setPayMethod(Constants.PayMethod.PAGE);
        order.setAmount(dto.getAmount());
        order.setSubject(dto.getSubject());
        order.setStatus(Constants.OrderStatus.UNPAID);
        // 关键差异：用请求带来的 notify_url / return_url，不用商户默认值
        order.setNotifyUrl(StringUtils.hasText(dto.getNotifyUrl()) ? dto.getNotifyUrl() : merchant.getNotifyUrl());
        order.setReturnUrl(StringUtils.hasText(dto.getReturnUrl()) ? dto.getReturnUrl() : merchant.getReturnUrl());
        order.setNotifyStatus(0);
        order.setNotifyCount(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(orderTimeoutMinutes));
        order.setClientIp(clientIp);
        order.setOrderSource(Constants.OrderSource.EPAY);

        // 易支付路径同样分配唯一 pay_amount
        BigDecimal payAmount = pendingPayAmountService.allocate(
                merchant.getId(), qrcode.getPayType(), dto.getAmount(),
                order.getOrderNo(), order.getExpireTime());
        order.setPayAmount(payAmount);

        try {
            this.save(order);
        } catch (RuntimeException e) {
            pendingPayAmountService.release(merchant.getId(), qrcode.getPayType(), payAmount, order.getOrderNo());
            throw e;
        }
        log.info("创建易支付订单成功: orderNo={}, amount={}, payAmount={}, source=epay",
                order.getOrderNo(), order.getAmount(), order.getPayAmount());

        PayResultVO vo = new PayResultVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOutTradeNo(order.getOutTradeNo());
        vo.setAmount(order.getAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setPayType(order.getPayType());
        vo.setPayMethod(order.getPayMethod());
        // MTM-176：和 native 下单口径保持一致，交给 Jackson 序列化成 ISO-8601 字符串
        vo.setExpireTime(order.getExpireTime());
        vo.setQrcodeUrl(qrcode.getQrcodeUrl());
        vo.setPayPageUrl(pageDomain + "/pay/" + order.getOrderNo());
        return vo;
    }

    @Override
    public PayOrder queryOrder(String orderNo) {
        return this.getOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getOrderNo, orderNo));
    }

    @Override
    public PayOrder getOrderDetail(String orderNo, Long merchantId) {
        PayOrder order = this.queryOrder(orderNo);
        if (order == null) {
            return null;
        }
        // 归属校验：merchantId != null 表示商户中心侧调用，只能看自己的订单，
        // 抛的异常和 confirmPay / closeOrder / resendNotify 三处保持一致。
        // 管理后台传 null，直接放行。
        if (merchantId != null && !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException("无权操作此订单");
        }
        // 补齐商户名和店铺名，跟列表接口口径保持一致
        if (order.getMerchantId() != null) {
            Merchant merchant = merchantMapper.selectById(order.getMerchantId());
            if (merchant != null) {
                order.setMerchantName(merchant.getMerchantName());
            }
        }
        if (order.getShopId() != null) {
            Shop shop = shopMapper.selectById(order.getShopId());
            if (shop != null) {
                order.setShopName(shop.getShopName());
            }
        }
        return order;
    }

    @Override
    public PayOrder queryOrderByOutTradeNo(String merchantNo, String outTradeNo) {
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getMerchantNo, merchantNo));
        if (merchant == null) {
            return null;
        }

        return this.getOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getMerchantId, merchant.getId())
                .eq(PayOrder::getOutTradeNo, outTradeNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPay(String orderNo, Long merchantId) {
        PayOrder order = this.queryOrder(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 权限校验
        if (merchantId != null && !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException("无权操作此订单");
        }

        // 已支付幂等直接返回：不再累加统计、不再发回调
        if (Constants.OrderStatus.PAID.equals(order.getStatus())) {
            log.info("订单已支付，跳过重复确认: orderNo={}", orderNo);
            return;
        }
        // 其它非 UNPAID（已过期 / 已关闭）视为异常
        if (!Constants.OrderStatus.UNPAID.equals(order.getStatus())) {
            throw new BusinessException("订单状态不正确");
        }

        // 乐观锁：把 status 从 UNPAID CAS 到 PAID。影响 0 行说明另一路已经把它标成已支付了，直接幂等返回
        LocalDateTime payTime = LocalDateTime.now();
        int affected = this.getBaseMapper().update(null, new LambdaUpdateWrapper<PayOrder>()
                .set(PayOrder::getStatus, Constants.OrderStatus.PAID)
                .set(PayOrder::getPayTime, payTime)
                .eq(PayOrder::getOrderNo, orderNo)
                .eq(PayOrder::getStatus, Constants.OrderStatus.UNPAID));
        if (affected == 0) {
            log.info("订单已被并发处理，本次幂等返回: orderNo={}", orderNo);
            return;
        }

        // 只有 CAS 赢的这一路才能继续做累加统计、释放占位、发回调
        // 重新读一次，拿到最新 pay_time 等字段，也顺便刷新回内存对象供后续使用
        PayOrder confirmed = this.queryOrder(orderNo);
        if (confirmed == null) {
            // 极不可能：CAS 刚成功、订单转瞬被物理删除；保守起见跳过后续统计
            return;
        }

        BigDecimal statAmount = confirmed.getPayAmount() != null ? confirmed.getPayAmount() : confirmed.getAmount();

        // 更新二维码统计
        PayQrcode qrcode = payQrcodeMapper.selectById(confirmed.getQrcodeId());
        if (qrcode != null) {
            qrcode.setTotalAmount(qrcode.getTotalAmount().add(statAmount));
            qrcode.setTotalCount(qrcode.getTotalCount() + 1);
            payQrcodeMapper.updateById(qrcode);
        }

        // 更新店铺统计
        Shop shop = shopMapper.selectById(confirmed.getShopId());
        if (shop != null) {
            shop.setTotalAmount(shop.getTotalAmount().add(statAmount));
            shop.setTotalCount(shop.getTotalCount() + 1);
            shopMapper.updateById(shop);
        }

        // 更新商户统计
        Merchant merchant = merchantMapper.selectById(confirmed.getMerchantId());
        if (merchant != null) {
            merchant.setTotalAmount(merchant.getTotalAmount().add(statAmount));
            merchant.setTotalCount(merchant.getTotalCount() + 1);
            merchantMapper.updateById(merchant);
        }

        // 释放 pay_amount 占位，让后来的订单能复用这个金额
        pendingPayAmountService.release(confirmed.getMerchantId(), confirmed.getPayType(),
                confirmed.getPayAmount(), confirmed.getOrderNo());

        log.info("订单支付确认成功: orderNo={}", orderNo);

        // 使用自定义线程池异步发送回调通知，提高响应速度
        final String finalOrderNo = orderNo;
        payNotifyExecutor.execute(() -> {
            try {
                doSendNotify(confirmed);
            } catch (Exception e) {
                log.error("异步发送回调通知异常: orderNo={}", finalOrderNo, e);
            }
        });
    }

    @Override
    public void closeOrder(String orderNo, Long merchantId) {
        PayOrder order = this.queryOrder(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 权限校验
        if (merchantId != null && !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException("无权操作此订单");
        }

        // 状态校验
        if (!Constants.OrderStatus.UNPAID.equals(order.getStatus())) {
            throw new BusinessException("订单状态不正确");
        }

        order.setStatus(Constants.OrderStatus.CLOSED);
        this.updateById(order);

        // 关单也要释放 pay_amount 占位
        pendingPayAmountService.release(order.getMerchantId(), order.getPayType(),
                order.getPayAmount(), order.getOrderNo());
    }

    @Override
    public Page<PayOrder> pageOrders(Page<PayOrder> page, Long merchantId, Long shopId, String orderNo, Integer status) {
        // 管理后台只有一个「平台/商户订单号」搜索框，两个字段任一命中都算，因此保留 OR 语义
        LambdaQueryWrapper<PayOrder> wrapper = new LambdaQueryWrapper<>();
        if (merchantId != null) {
            wrapper.eq(PayOrder::getMerchantId, merchantId);
        }
        if (shopId != null) {
            wrapper.eq(PayOrder::getShopId, shopId);
        }
        if (StringUtils.hasText(orderNo)) {
            wrapper.and(w -> w.like(PayOrder::getOrderNo, orderNo)
                    .or().like(PayOrder::getOutTradeNo, orderNo));
        }
        if (status != null) {
            wrapper.eq(PayOrder::getStatus, status);
        }
        wrapper.orderByDesc(PayOrder::getCreateTime);
        return runPageAndEnrichNames(page, wrapper);
    }

    @Override
    public Page<PayOrder> pageMerchantOrders(Page<PayOrder> page, Long merchantId, Long shopId,
                                             String orderNo, String outTradeNo, String subject,
                                             String payType, Integer status) {
        // 商户端搜索框拆成独立字段：平台订单号 / 商户订单号 / 商品名称 各自模糊匹配；
        // 支付类型、状态、店铺是精确匹配。多个筛选之间取 AND。
        LambdaQueryWrapper<PayOrder> wrapper = new LambdaQueryWrapper<>();
        if (merchantId != null) {
            wrapper.eq(PayOrder::getMerchantId, merchantId);
        }
        if (shopId != null) {
            wrapper.eq(PayOrder::getShopId, shopId);
        }
        if (StringUtils.hasText(orderNo)) {
            wrapper.like(PayOrder::getOrderNo, orderNo);
        }
        if (StringUtils.hasText(outTradeNo)) {
            wrapper.like(PayOrder::getOutTradeNo, outTradeNo);
        }
        if (StringUtils.hasText(subject)) {
            wrapper.like(PayOrder::getSubject, subject);
        }
        if (StringUtils.hasText(payType)) {
            wrapper.eq(PayOrder::getPayType, payType);
        }
        if (status != null) {
            wrapper.eq(PayOrder::getStatus, status);
        }
        wrapper.orderByDesc(PayOrder::getCreateTime);
        return runPageAndEnrichNames(page, wrapper);
    }

    /**
     * 分页 + 回填商户名 / 店铺名，两个 page 查询共用一段收尾逻辑
     */
    private Page<PayOrder> runPageAndEnrichNames(Page<PayOrder> page, LambdaQueryWrapper<PayOrder> wrapper) {
        Page<PayOrder> result = this.page(page, wrapper);
        result.getRecords().forEach(order -> {
            Merchant merchant = merchantMapper.selectById(order.getMerchantId());
            if (merchant != null) {
                order.setMerchantName(merchant.getMerchantName());
            }
            Shop shop = shopMapper.selectById(order.getShopId());
            if (shop != null) {
                order.setShopName(shop.getShopName());
            }
        });
        return result;
    }

    @Override
    public PayOrder getPayPageData(String orderNo) {
        PayOrder order = this.queryOrder(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 检查订单是否过期
        if (LocalDateTime.now().isAfter(order.getExpireTime())) {
            if (Constants.OrderStatus.UNPAID.equals(order.getStatus())) {
                order.setStatus(Constants.OrderStatus.EXPIRED);
                this.updateById(order);
                // 支付页翻到时才发现的过期，也要释放占位
                pendingPayAmountService.release(order.getMerchantId(), order.getPayType(),
                        order.getPayAmount(), order.getOrderNo());
            }
        }

        return order;
    }

    @Override
    public void processExpiredOrders() {
        // 查询过期的待支付订单
        List<PayOrder> expiredOrders = this.list(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, Constants.OrderStatus.UNPAID)
                .lt(PayOrder::getExpireTime, LocalDateTime.now()));

        for (PayOrder order : expiredOrders) {
            order.setStatus(Constants.OrderStatus.EXPIRED);
            this.updateById(order);
            // 过期订单释放它占用的 pay_amount
            pendingPayAmountService.release(order.getMerchantId(), order.getPayType(),
                    order.getPayAmount(), order.getOrderNo());
            log.info("订单已过期: orderNo={}", order.getOrderNo());
        }

        // 兜底：清理占位表里已过期但因为异常路径没释放的残留（比如进程崩过一次）
        pendingPayAmountService.cleanExpired(LocalDateTime.now());
    }

    @Override
    public void sendNotify(String orderNo) {
        PayOrder order = this.queryOrder(orderNo);
        // 使用自定义线程池异步发送
        payNotifyExecutor.execute(() -> {
            try {
                doSendNotify(order);
            } catch (Exception e) {
                log.error("异步发送回调通知异常: orderNo={}", order.getOrderNo(), e);
            }
        });
    }

    /**
     * 实际执行发送回调通知的方法
     * 按 order.orderSource 分派：
     * - epay：GET 请求 + 易支付签名（{@link EpaySignUtil}）
     * - native / null（存量老订单）：POST 请求 + FastPay 签名（{@link SignUtil}），保持完全兼容
     */
    private void doSendNotify(PayOrder order) {
        long startTime = System.currentTimeMillis();
        if (order == null || !StringUtils.hasText(order.getNotifyUrl())) {
            return;
        }

        // 获取商户信息
        Merchant merchant = merchantMapper.selectById(order.getMerchantId());
        if (merchant == null) {
            return;
        }

        try {
            String response;
            if (Constants.OrderSource.EPAY.equals(order.getOrderSource())) {
                response = sendEpayNotify(order, merchant);
            } else {
                response = sendNativeNotify(order, merchant);
            }

            log.info("发送回调通知成功: orderNo={}, source={}, response={}, 耗时{}ms",
                    order.getOrderNo(), order.getOrderSource(), response, System.currentTimeMillis() - startTime);

            order.setNotifyStatus("success".equalsIgnoreCase(response) ? 1 : 2);
            order.setNotifyCount(order.getNotifyCount() + 1);
            order.setLastNotifyTime(LocalDateTime.now());
            // 存商户返回内容，截断到 NOTIFY_RESULT_MAX_LEN；成功路径下 notify_error 清空
            order.setNotifyResult(truncate(response, NOTIFY_RESULT_MAX_LEN));
            order.setNotifyError(null);
            this.updateById(order);

        } catch (Exception e) {
            log.error("发送回调通知失败: orderNo={}, source={}, error={}, 耗时{}ms",
                    order.getOrderNo(), order.getOrderSource(), e.getMessage(), System.currentTimeMillis() - startTime);
            order.setNotifyStatus(2);
            order.setNotifyCount(order.getNotifyCount() + 1);
            order.setLastNotifyTime(LocalDateTime.now());
            // 存报错信息（异常类名 + message，截断到 NOTIFY_ERROR_MAX_LEN）；失败路径下 notify_result 清空
            order.setNotifyResult(null);
            order.setNotifyError(truncate(formatError(e), NOTIFY_ERROR_MAX_LEN));
            this.updateById(order);
        }
    }

    /** notify_result 最大存储长度，超出直接截断，跟建表脚本的 VARCHAR(1000) 对齐 */
    private static final int NOTIFY_RESULT_MAX_LEN = 1000;

    /** notify_error 最大存储长度，超出直接截断，跟建表脚本的 VARCHAR(500) 对齐 */
    private static final int NOTIFY_ERROR_MAX_LEN = 500;

    /**
     * 长度超限时按 code point 边界截断，避免把 UTF-16 代理对切成半个字符导致乱码。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        // 若截断点正好落在代理对中间，往前退一格
        int end = maxLen;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 把异常拍成 "类名: message" 一行，方便直接展示给运营看，不要塞完整栈。
     */
    private static String formatError(Throwable e) {
        String msg = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (msg == null || msg.isEmpty()) {
            return name;
        }
        return name + ": " + msg;
    }

    /**
     * 原生 FastPay 格式的回调：POST 表单 + 大写 MD5 签名，行为和历史版本完全一致
     */
    private String sendNativeNotify(PayOrder order, Merchant merchant) {
        TreeMap<String, Object> params = new TreeMap<>();
        params.put("merchantNo", merchant.getMerchantNo());
        params.put("orderNo", order.getOrderNo());
        params.put("outTradeNo", order.getOutTradeNo());
        // 语义约定：amount = 商户下单时报的原始金额；payAmount = 我们微调后的实付金额
        params.put("amount", order.getAmount());
        params.put("payAmount", order.getPayAmount());
        params.put("payType", order.getPayType());
        params.put("status", String.valueOf(order.getStatus()));
        params.put("payTime", order.getPayTime() != null ? order.getPayTime().toString() : "");
        params.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        if (StringUtils.hasText(order.getExtParam())) {
            params.put("extParam", order.getExtParam());
        }
        String sign = SignUtil.generateSign(params, merchant.getApiSecret());
        params.put("sign", sign);
        return HttpUtil.post(order.getNotifyUrl(), params, 5000);
    }

    /**
     * 彩虹易支付格式的回调：GET 查询串 + 小写 MD5 签名。
     * 关键点：money 字段回传"原始订单金额" order.getAmount()，不能回传微调后的 pay_amount。
     * sub2api 那边记的订单是 10 元，我们回传 10.01 它会认为金额不符而拒绝，
     * 导致明明付款成功却不给用户充值。
     */
    private String sendEpayNotify(PayOrder order, Merchant merchant) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("pid", merchant.getMerchantNo());
        params.put("trade_no", order.getOrderNo());
        params.put("out_trade_no", order.getOutTradeNo());
        params.put("type", order.getPayType());
        params.put("name", order.getSubject() == null ? "" : order.getSubject());
        params.put("money", order.getAmount().toPlainString());
        params.put("trade_status", "TRADE_SUCCESS");

        String sign = EpaySignUtil.generateSign(params, merchant.getApiSecret());
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        StringBuilder url = new StringBuilder(order.getNotifyUrl());
        url.append(order.getNotifyUrl().contains("?") ? '&' : '?');
        boolean first = true;
        for (java.util.Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return HttpUtil.get(url.toString(), 5000);
    }

    @Override
    public void updateReturnUrl(String orderNo, String returnUrl) {
        PayOrder order = this.queryOrder(orderNo);
        if (order != null) {
            order.setReturnUrl(returnUrl);
            this.updateById(order);
            log.info("更新订单returnUrl: orderNo={}, returnUrl={}", orderNo, returnUrl);
        }
    }

    @Override
    public void resendNotify(String orderNo, Long merchantId) {
        PayOrder order = this.queryOrder(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 权限校验
        if (merchantId != null && !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException("无权操作此订单");
        }

        // 状态校验：只有已支付的订单才能重发通知
        if (!Constants.OrderStatus.PAID.equals(order.getStatus())) {
            throw new BusinessException("订单状态不正确，只有已支付订单才能重发通知");
        }

        // 发送通知
        sendNotify(orderNo);
    }

    @Override
    public void processFailedNotify() {
        // 查询需要重发的订单：已支付、通知失败、通知次数小于5次
        List<PayOrder> failedOrders = this.list(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, Constants.OrderStatus.PAID)
                .eq(PayOrder::getNotifyStatus, 2)  // 通知失败
                .lt(PayOrder::getNotifyCount, 5)   // 通知次数小于5次
                .isNotNull(PayOrder::getNotifyUrl)
                .ne(PayOrder::getNotifyUrl, "")
                .isNotNull(PayOrder::getLastNotifyTime)
        );

        if (failedOrders.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int retryCount = 0;

        for (PayOrder order : failedOrders) {
            // 根据通知次数计算重试间隔（递增策略：1分钟、2分钟、4分钟、8分钟、16分钟）
            int intervalMinutes = (int) Math.pow(2, order.getNotifyCount());
            LocalDateTime nextRetryTime = order.getLastNotifyTime().plusMinutes(intervalMinutes);

            // 如果还没到重试时间，跳过
            if (now.isBefore(nextRetryTime)) {
                continue;
            }

            try {
                doSendNotify(order);
                retryCount++;
                log.info("重发回调通知: orderNo={}, 第{}次重试", order.getOrderNo(), order.getNotifyCount());
            } catch (Exception e) {
                log.error("重发回调通知失败: orderNo={}, error={}", order.getOrderNo(), e.getMessage());
            }
        }

        if (retryCount > 0) {
            log.info("本次处理失败回调通知完成，共重发{}条", retryCount);
        }
    }
}
