package com.fastpay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fastpay.common.BusinessException;
import com.fastpay.entity.UnmatchedNotify;
import com.fastpay.mapper.UnmatchedNotifyMapper;
import com.fastpay.service.UnmatchedNotifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
public class UnmatchedNotifyServiceImpl implements UnmatchedNotifyService {

    /** 待人工处理 */
    public static final Integer STATUS_PENDING = 0;
    /** 已人工处理 */
    public static final Integer STATUS_HANDLED = 1;
    /** 已忽略 */
    public static final Integer STATUS_IGNORED = 2;

    private final UnmatchedNotifyMapper mapper;

    public UnmatchedNotifyServiceImpl(UnmatchedNotifyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void recordUnmatched(BigDecimal amount, String payType, Long merchantId, Long channelId,
                                String rawMessage, LocalDateTime notifyTime) {
        UnmatchedNotify row = new UnmatchedNotify();
        row.setAmount(amount);
        row.setPayType(payType);
        row.setMerchantId(merchantId);
        row.setChannelId(channelId);
        row.setRawMessage(rawMessage);
        row.setNotifyTime(notifyTime != null ? notifyTime : LocalDateTime.now());
        row.setHandleStatus(STATUS_PENDING);
        mapper.insert(row);
        log.warn("记录未匹配收款通知: amount={}, payType={}, merchantId={}, channelId={}",
                amount, payType, merchantId, channelId);
    }

    @Override
    public Page<UnmatchedNotify> pageUnmatched(Page<UnmatchedNotify> page, Integer handleStatus, Long merchantId) {
        LambdaQueryWrapper<UnmatchedNotify> wrapper = new LambdaQueryWrapper<>();
        if (handleStatus != null) {
            wrapper.eq(UnmatchedNotify::getHandleStatus, handleStatus);
        }
        if (merchantId != null) {
            wrapper.eq(UnmatchedNotify::getMerchantId, merchantId);
        }
        wrapper.orderByDesc(UnmatchedNotify::getCreateTime);
        return mapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional
    public void handleUnmatched(Long id, String remark, String handledOrderNo) {
        UnmatchedNotify row = mapper.selectById(id);
        if (row == null) {
            throw new BusinessException("未匹配通知不存在");
        }
        if (!STATUS_PENDING.equals(row.getHandleStatus())) {
            throw new BusinessException("该通知已经处理过");
        }
        row.setHandleStatus(STATUS_HANDLED);
        row.setHandleRemark(remark);
        row.setHandledOrderNo(handledOrderNo);
        row.setHandleTime(LocalDateTime.now());
        mapper.updateById(row);
    }

    @Override
    @Transactional
    public void ignoreUnmatched(Long id, String remark) {
        UnmatchedNotify row = mapper.selectById(id);
        if (row == null) {
            throw new BusinessException("未匹配通知不存在");
        }
        if (!STATUS_PENDING.equals(row.getHandleStatus())) {
            throw new BusinessException("该通知已经处理过");
        }
        row.setHandleStatus(STATUS_IGNORED);
        row.setHandleRemark(remark);
        row.setHandleTime(LocalDateTime.now());
        mapper.updateById(row);
    }
}
