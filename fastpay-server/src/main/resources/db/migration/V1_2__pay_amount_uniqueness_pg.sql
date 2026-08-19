-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.2（PostgreSQL）
-- 主题：解决"两人同价撞单认错人"（MTM-170）
-- 说明：老库升级用。新库直接跑 init-pg.sql 已经是新结构，不需要这里
--
-- 变更点：见 MySQL 版
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   psql -h <host> -U <user> -d bigbear_fastpay -f V1_2__pay_amount_uniqueness_pg.sql
-- =====================================================

-- 1. 待支付金额占位表
CREATE TABLE IF NOT EXISTS fp_pending_pay_amount (
    id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    pay_type VARCHAR(20) NOT NULL,
    pay_amount DECIMAL(10,2) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    expire_time TIMESTAMP NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pending_pay_amount UNIQUE (merchant_id, pay_type, pay_amount)
);
CREATE INDEX IF NOT EXISTS idx_pending_order_no ON fp_pending_pay_amount (order_no);
CREATE INDEX IF NOT EXISTS idx_pending_expire_time ON fp_pending_pay_amount (expire_time);

COMMENT ON TABLE fp_pending_pay_amount IS '待支付金额占位表 - 保证同商户同支付方式下未支付订单的 pay_amount 唯一';
COMMENT ON COLUMN fp_pending_pay_amount.pay_amount IS '实际应付金额（微调后落定的值）';
COMMENT ON COLUMN fp_pending_pay_amount.expire_time IS '关联订单的过期时间，兜底清理用';

-- 2. 未匹配收款通知表
CREATE TABLE IF NOT EXISTS fp_unmatched_notify (
    id BIGSERIAL PRIMARY KEY,
    amount DECIMAL(10,2) NOT NULL,
    pay_type VARCHAR(20) NOT NULL,
    merchant_id BIGINT DEFAULT NULL,
    channel_id BIGINT DEFAULT NULL,
    raw_message TEXT,
    notify_time TIMESTAMP DEFAULT NULL,
    handle_status SMALLINT DEFAULT 0,
    handle_remark VARCHAR(500) DEFAULT NULL,
    handled_order_no VARCHAR(32) DEFAULT NULL,
    handle_time TIMESTAMP DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_unmatched_handle_status ON fp_unmatched_notify (handle_status);
CREATE INDEX IF NOT EXISTS idx_unmatched_merchant_id ON fp_unmatched_notify (merchant_id);
CREATE INDEX IF NOT EXISTS idx_unmatched_create_time ON fp_unmatched_notify (create_time);

COMMENT ON TABLE fp_unmatched_notify IS '未匹配收款通知 - 钱到了但认不到订单的记录';
COMMENT ON COLUMN fp_unmatched_notify.handle_status IS '处理状态：0-待处理 1-已人工处理 2-已忽略';
COMMENT ON COLUMN fp_unmatched_notify.raw_message IS '原始通知内容（title / msg / receiveTime 汇总）';
COMMENT ON COLUMN fp_unmatched_notify.handled_order_no IS '人工对应到的平台订单号';

-- 3. 老 UNPAID 订单补齐 pay_amount
UPDATE fp_pay_order SET pay_amount = amount WHERE status = 0 AND pay_amount IS NULL;

-- 3b. 已支付但 pay_amount 是 NULL 的老订单顺手补齐
UPDATE fp_pay_order SET pay_amount = amount WHERE status = 1 AND pay_amount IS NULL;

-- 4a. 老 UNPAID 订单里如果同一 (merchant_id, pay_type, pay_amount) 有多笔（就是历史遗留的撞单），
-- 只保留最早创建的那笔（id 最小），其余直接关掉：status = 3（已关闭）
UPDATE fp_pay_order
SET status = 3
WHERE status = 0
  AND id NOT IN (
      SELECT MIN(id)
      FROM fp_pay_order
      WHERE status = 0
      GROUP BY merchant_id, pay_type, pay_amount
  );

-- 4b. 把剩下的老 UNPAID 订单塞进占位表，让新版后端一启动就能看见"这些金额已被老单占着"，
-- 从而给新订单自动微调避开。ON CONFLICT 兜底防止本脚本被重复执行时报错
INSERT INTO fp_pending_pay_amount (merchant_id, pay_type, pay_amount, order_no, expire_time)
SELECT merchant_id, pay_type, pay_amount, order_no, expire_time
FROM fp_pay_order
WHERE status = 0
ON CONFLICT (merchant_id, pay_type, pay_amount) DO NOTHING;
