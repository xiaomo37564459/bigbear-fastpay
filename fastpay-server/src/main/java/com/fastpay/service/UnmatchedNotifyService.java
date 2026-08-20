package com.fastpay.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fastpay.entity.UnmatchedNotify;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 未匹配收款通知服务：付款通知找不到匹配订单时落表 + 管理后台读表 / 人工处理
 */
public interface UnmatchedNotifyService {

    /** 未匹配通知落表 */
    void recordUnmatched(BigDecimal amount, String payType, Long merchantId, Long channelId,
                        String rawMessage, LocalDateTime notifyTime);

    /**
     * 分页查询未匹配通知
     *
     * @param handleStatus 处理状态过滤，null 表示不限
     */
    Page<UnmatchedNotify> pageUnmatched(Page<UnmatchedNotify> page, Integer handleStatus, Long merchantId);

    /**
     * 人工把某条未匹配通知处理掉：标记为已人工处理，写入处理备注和对应到的订单号（可选）。
     */
    void handleUnmatched(Long id, String remark, String handledOrderNo);

    /**
     * 人工把某条未匹配通知忽略掉：标记为已忽略（比如是其他账户流水，或者是重复通知）。
     */
    void ignoreUnmatched(Long id, String remark);
}
