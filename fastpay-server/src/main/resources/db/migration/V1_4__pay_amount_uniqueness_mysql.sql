-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.4（MySQL / MariaDB）
-- 主题：解决"两人同价撞单认错人"（MTM-170）
--
-- 【本文件原来叫 V1_2，2026-08-20 改成 V1_4】
--   原因：主干上「密码存法换 bcrypt」那份脚本也叫 V1_2，两份内容不同的脚本顶着同一个编号，
--   上线时操作的人无法判断该跑哪个。V1_3 已被「订单回调结果落库」占用，故本文件顺延为 V1_4。
--   编号只是**唯一文件名**，不代表发布先后：本脚本随 v1.5.1 发布，V1_3 随 v1.6.0 发布。
--   四个脚本互相独立、都可重复执行，跑的先后顺序不影响结果。
--   哪个版本要跑哪个脚本，以 docs/DEPLOY.md 里那张「发布版本 → 迁移脚本」表为准。
-- 说明：老库升级用。新库直接跑 init.sql 已经是新结构，不需要这里
--
-- 变更点：
--   1. 新增 fp_pending_pay_amount 表 —— 待支付金额占位表
--      同一商户 + 同一支付方式下未过期未支付订单的 pay_amount 必须唯一，
--      由唯一索引 uk_pending (merchant_id, pay_type, pay_amount) 兜底
--   2. 新增 fp_unmatched_notify 表 —— 未匹配收款通知
--      "钱到了但认不到订单"的记录，管理后台能查询、人工处理
--   3. 补齐历史订单的 pay_amount：把 status=0 且 pay_amount IS NULL 的老订单
--      pay_amount 补成 amount，避免上线切换到"按 pay_amount 匹配"后老订单永远匹配不到；
--      已支付的老订单顺手补齐，历史对账口径统一
--   4. 老 UNPAID 订单冲突消解 + 塞进占位表：同一 (merchant_id, pay_type, pay_amount) 组下
--      若有多笔老 UNPAID，只保留最早创建的那笔，其余直接关掉（避免上线一瞬间就复发撞单）；
--      被保留的那笔再插入占位表 fp_pending_pay_amount，让上线后新订单会自动避开这些金额
--      （不然升级后的短暂窗口里，新订单查占位表"金额没人占"→ 又拿到老单同金额 → bug 复发）
--
-- ⚠️ 这个脚本会改动已有订单数据（不只是加表加列），执行前先统计受影响行数：
--   -- 会被回填 pay_amount 的订单数
--   SELECT count(*) FROM fp_pay_order WHERE status IN (0,1) AND pay_amount IS NULL;
--   -- 会被强制关掉（status 0 -> 3）的重复未支付订单数
--   SELECT count(*) FROM (SELECT id, merchant_id, pay_type, COALESCE(pay_amount, amount) AS pa, status FROM fp_pay_order) o
--   WHERE o.status = 0 AND o.id NOT IN
--     (SELECT MIN(id) FROM (SELECT id, merchant_id, pay_type, COALESCE(pay_amount, amount) AS pa, status FROM fp_pay_order) x
--      WHERE x.status = 0 GROUP BY x.merchant_id, x.pay_type, x.pa);
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   mysql -h <host> -P 3306 -u <user> -p bigbear_fastpay < V1_4__pay_amount_uniqueness_mysql.sql
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

-- 3b. 已支付订单如果 pay_amount 是 NULL，也顺手补一下，历史对账口径统一
UPDATE fp_pay_order
SET pay_amount = amount
WHERE status = 1 AND pay_amount IS NULL;

-- 4a. 老 UNPAID 订单里如果同一 (merchant_id, pay_type, pay_amount) 有多笔（就是历史遗留的撞单），
-- 只保留最早创建的那笔（id 最小），其余直接关掉：status = 3（已关闭）
-- 说明：MySQL 不允许 UPDATE 的 WHERE 里 SELECT 同一张表，用 "SELECT 套一层" 骗过语法限制
UPDATE fp_pay_order
SET status = 3
WHERE status = 0
  AND id NOT IN (
      SELECT keep_id FROM (
          SELECT MIN(id) AS keep_id
          FROM fp_pay_order
          WHERE status = 0
          GROUP BY merchant_id, pay_type, pay_amount
      ) AS keepers
  );

-- 4b. 把剩下的老 UNPAID 订单塞进占位表，让新版后端一启动就能看见"这些金额已被老单占着"，
-- 从而给新订单自动微调避开。ON DUPLICATE KEY 兜底防止本脚本被重复执行时报错
INSERT INTO fp_pending_pay_amount (merchant_id, pay_type, pay_amount, order_no, expire_time)
SELECT merchant_id, pay_type, pay_amount, order_no, expire_time
FROM fp_pay_order
WHERE status = 0
ON DUPLICATE KEY UPDATE order_no = VALUES(order_no);
