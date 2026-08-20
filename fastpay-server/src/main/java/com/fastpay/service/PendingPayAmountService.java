package com.fastpay.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待支付金额占位服务
 *
 * 用一张单独的 fp_pending_pay_amount 表加唯一索引 (merchant_id, pay_type, pay_amount)，
 * 把「同一商户+同一支付方式下未过期未支付订单，pay_amount 必须唯一」这条规则交给数据库来兜。
 * 下单时先在这张表里占位、成功再落 fp_pay_order；订单结束（已支付 / 关闭 / 过期）时释放。
 */
public interface PendingPayAmountService {

    /**
     * 分配一个 pay_amount：
     *   1. 从 amount 开始，依次尝试 amount, amount±0.01, amount±0.02, ..., amount±0.99（跳过 <=0 的）
     *   2. 每次候选都尝试往占位表插一行，唯一索引冲突就换下一个候选
     *   3. 全部候选都被占用 → 抛业务异常，让上游返回「稍后重试」
     *
     * @return 分配到的 pay_amount（可能就是 amount 本身）
     */
    BigDecimal allocate(Long merchantId, String payType, BigDecimal amount,
                        String orderNo, LocalDateTime expireTime);

    /**
     * 释放某笔订单持有的 pay_amount 占位。幂等，不存在也不报错。
     *
     * 关键：删除条件除了 (merchant_id, pay_type, pay_amount) 之外，必须再加上 order_no。
     * 否则并发场景下（比如同一笔已过期订单被两个入口同时触发释放，
     * 中间又有一笔新订单刚抢到了同一金额），第二次释放会把新订单的占位一起删掉，
     * 撞单场景又会原样复发。
     *
     * @param orderNo 必须传当前订单号，不允许 null / 空串（这是防止误删新订单占位的关键）
     */
    void release(Long merchantId, String payType, BigDecimal payAmount, String orderNo);

    /**
     * 清理占位表里已经过期的残留（防止某些异常路径漏 release，长期堆积把候选耗光）。
     * 由定时任务调用。
     */
    int cleanExpired(LocalDateTime cutoff);
}
