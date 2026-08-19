-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.2（MySQL / MariaDB）
-- 主题：解决"两人同价撞单认错人"（MTM-170）
-- 说明：老库升级用。新库直接跑 init.sql 已经是新结构，不需要这里
--
-- 变更点：
--   1. 新增 fp_pending_pay_amount 表 —— 待支付金额占位表
--      同一商户 + 同一支付方式下未过期未支付订单的 pay_amount 必须唯一，
--      由唯一索引 uk_pending (merchant_id, pay_type, pay_amount) 兜底
--   2. 新增 fp_unmatched_notify 表 —— 未匹配收款通知
--      "钱到了但认不到订单"的记录，管理后台能查询、人工处理
--   3. 补齐历史订单的 pay_amount：把 status=0 且 pay_amount IS NULL 的老订单
--      pay_amount 补成 amount，避免上线切换到"按 pay_amount 匹配"后老订单永远匹配不到
--   4. 关掉可能有 pay_amount 撞车的老 UNPAID 订单（可选，防止上线一瞬间的匹配错乱）
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   mysql -h <host> -P 3306 -u <user> -p bigbear_fastpay < V1_2__pay_amount_uniqueness_mysql.sql
--
-- 注意：本项目当前生产环境跑的是 PostgreSQL，MySQL 版仅为老装机 / 本地开发环境预留。
-- =====================================================

-- 1. 待支付金额占位表
CREATE TABLE IF NOT EXISTS `fp_pending_pay_amount` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `merchant_id` BIGINT NOT NULL COMMENT '商户ID',
    `pay_type` VARCHAR(20) NOT NULL COMMENT '支付类型：wxpay/alipay',
    `pay_amount` DECIMAL(10,2) NOT NULL COMMENT '实际应付金额（微调后落定的值）',
    `order_no` VARCHAR(32) NOT NULL COMMENT '关联的平台订单号',
    `expire_time` DATETIME NOT NULL COMMENT '关联订单的过期时间，兜底清理用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pending_pay_amount` (`merchant_id`, `pay_type`, `pay_amount`) COMMENT '同商户+同支付方式下未支付订单 pay_amount 必须唯一',
    KEY `idx_pending_order_no` (`order_no`),
    KEY `idx_pending_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待支付金额占位表 - 保证同商户同支付方式下未支付订单的 pay_amount 唯一';

-- 2. 未匹配收款通知表
CREATE TABLE IF NOT EXISTS `fp_unmatched_notify` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '收到的收款金额',
    `pay_type` VARCHAR(20) NOT NULL COMMENT '支付类型：wxpay/alipay',
    `merchant_id` BIGINT DEFAULT NULL COMMENT '商户ID（通过 channelId 反查）',
    `channel_id` BIGINT DEFAULT NULL COMMENT '商户通道ID',
    `raw_message` TEXT COMMENT '原始通知内容（title / msg / receiveTime 汇总）',
    `notify_time` DATETIME DEFAULT NULL COMMENT '监听软件收到通知的时间',
    `handle_status` TINYINT DEFAULT 0 COMMENT '处理状态：0-待处理 1-已人工处理 2-已忽略',
    `handle_remark` VARCHAR(500) DEFAULT NULL COMMENT '处理备注',
    `handled_order_no` VARCHAR(32) DEFAULT NULL COMMENT '人工对应到的平台订单号',
    `handle_time` DATETIME DEFAULT NULL COMMENT '处理时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_unmatched_handle_status` (`handle_status`),
    KEY `idx_unmatched_merchant_id` (`merchant_id`),
    KEY `idx_unmatched_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='未匹配收款通知 - 钱到了但认不到订单的记录';

-- 3. 老 UNPAID 订单补齐 pay_amount，避免上线切换匹配规则后老订单永远匹配不到
UPDATE fp_pay_order
SET pay_amount = amount
WHERE status = 0 AND pay_amount IS NULL;

-- 4. 已支付订单如果 pay_amount 是 NULL，也顺手补一下，让历史对账口径统一
UPDATE fp_pay_order
SET pay_amount = amount
WHERE status = 1 AND pay_amount IS NULL;
