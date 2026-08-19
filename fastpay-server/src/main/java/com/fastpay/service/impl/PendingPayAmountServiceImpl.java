package com.fastpay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fastpay.common.BusinessException;
import com.fastpay.entity.PendingPayAmount;
import com.fastpay.mapper.PendingPayAmountMapper;
import com.fastpay.service.PendingPayAmountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 待支付金额占位服务实现。
 *
 * 关键点：tryReserve 走 {@link PendingPayAmountReservationHelper}，那里用 REQUIRES_NEW 独立事务
 * 包住 INSERT。这样即使唯一索引冲突把子事务变成 aborted 状态（PostgreSQL 的特性），
 * 也不会污染下单主流程的外层事务，主流程可以继续换金额重试。
 * （抽到另一个 Bean 是为了绕过 Spring 同类内部调用不走代理、@Transactional 失效的坑。）
 */
@Slf4j
@Service
public class PendingPayAmountServiceImpl implements PendingPayAmountService {

    /** 候选微调步长最大到 0.99 元 */
    private static final int MAX_OFFSET_CENTS = 99;
    /** 金额小数位数（DECIMAL(10,2)） */
    private static final int AMOUNT_SCALE = 2;

    private final PendingPayAmountMapper mapper;
    private final PendingPayAmountReservationHelper reservationHelper;

    public PendingPayAmountServiceImpl(PendingPayAmountMapper mapper,
                                       PendingPayAmountReservationHelper reservationHelper) {
        this.mapper = mapper;
        this.reservationHelper = reservationHelper;
    }

    @Override
    public BigDecimal allocate(Long merchantId, String payType, BigDecimal amount,
                               String orderNo, LocalDateTime expireTime) {
        BigDecimal base = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

        // 优先尝试原始金额；不成再依次 +0.01, -0.01, +0.02, -0.02, ...
        if (base.signum() > 0 && reservationHelper.tryReserve(merchantId, payType, base, orderNo, expireTime)) {
            return base;
        }
        for (int cents = 1; cents <= MAX_OFFSET_CENTS; cents++) {
            BigDecimal delta = BigDecimal.valueOf(cents, AMOUNT_SCALE);
            BigDecimal up = base.add(delta);
            if (up.signum() > 0 && reservationHelper.tryReserve(merchantId, payType, up, orderNo, expireTime)) {
                return up;
            }
            BigDecimal down = base.subtract(delta);
            if (down.signum() > 0 && reservationHelper.tryReserve(merchantId, payType, down, orderNo, expireTime)) {
                return down;
            }
        }
        // 极端情况：这个商户 + 支付方式下，附近 ±0.99 元的所有金额都被并发订单占了
        // 让用户稍等重试；比返回一个重复金额安全得多
        log.warn("pay_amount 候选被占满，merchantId={}, payType={}, baseAmount={}",
                merchantId, payType, base);
        throw new BusinessException("当前支付通道并发过高，请稍等几秒后重试下单");
    }

    @Override
    @Transactional
    public void release(Long merchantId, String payType, BigDecimal payAmount, String orderNo) {
        if (payAmount == null) {
            return;
        }
        if (!StringUtils.hasText(orderNo)) {
            // 没订单号就无脑按金额删是危险的：并发下会误删刚抢到同金额的新订单占位。
            // 现在所有调用方都能拿到订单号，缺失一定是编码疏漏，早失败比默默出错好。
            log.error("release 缺失 orderNo，拒绝执行以防误删新订单占位: merchantId={}, payType={}, payAmount={}",
                    merchantId, payType, payAmount);
            return;
        }
        BigDecimal normalized = payAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        mapper.delete(new LambdaQueryWrapper<PendingPayAmount>()
                .eq(PendingPayAmount::getMerchantId, merchantId)
                .eq(PendingPayAmount::getPayType, payType)
                .eq(PendingPayAmount::getPayAmount, normalized)
                .eq(PendingPayAmount::getOrderNo, orderNo));
    }

    @Override
    @Transactional
    public int cleanExpired(LocalDateTime cutoff) {
        return mapper.delete(new LambdaQueryWrapper<PendingPayAmount>()
                .lt(PendingPayAmount::getExpireTime, cutoff));
    }
}
