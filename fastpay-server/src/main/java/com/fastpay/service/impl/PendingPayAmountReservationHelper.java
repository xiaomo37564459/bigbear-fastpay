package com.fastpay.service.impl;

import com.fastpay.entity.PendingPayAmount;
import com.fastpay.mapper.PendingPayAmountMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * pay_amount 占位单次尝试的独立 Bean。
 * 单独抽出来是为了让 REQUIRES_NEW 真的生效——Spring 的 @Transactional 走的是代理，
 * 同一个 Bean 内部 this. 调用会绕过代理，propagation 就没意义了。
 */
@Component
public class PendingPayAmountReservationHelper {

    private final PendingPayAmountMapper mapper;

    public PendingPayAmountReservationHelper(PendingPayAmountMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 单次尝试预定一个 pay_amount。独立事务；重复键返回 false 让调用方换下一个候选。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(Long merchantId, String payType, BigDecimal payAmount,
                              String orderNo, LocalDateTime expireTime) {
        try {
            PendingPayAmount row = new PendingPayAmount();
            row.setMerchantId(merchantId);
            row.setPayType(payType);
            row.setPayAmount(payAmount);
            row.setOrderNo(orderNo);
            row.setExpireTime(expireTime);
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
