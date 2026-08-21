-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.5（MySQL / MariaDB）—— URL 列加宽到 2000 字符
-- 说明：老库升级用。新库直接执行 init.sql 就是新结构，不需要跑这里。
--
-- 变更点：
--   1. fp_pay_order.notify_url  从 VARCHAR(255) 放宽到 VARCHAR(2000)
--   2. fp_pay_order.return_url  从 VARCHAR(255) 放宽到 VARCHAR(2000)
--   3. fp_merchant.notify_url   从 VARCHAR(255) 放宽到 VARCHAR(2000)
--   4. fp_merchant.return_url   从 VARCHAR(255) 放宽到 VARCHAR(2000)
--
-- 详见 V1_5__widen_url_columns_pg.sql 头部说明（背景 / 兼容说明 / 幂等性），MySQL
-- 版只是数据库方言不同，两个脚本一一对应。
--
-- 注意：本项目当前生产环境跑的是 PostgreSQL，MySQL 版仅为老装机或本地 MySQL 部署预留。
--
-- 兼容说明（MySQL 特有）：
--   - MySQL 5.7 / 8.0 里 VARCHAR 加宽（且原宽度 ≤ 255 → 新宽度 ≤ 大约 21844 字节）
--     只改元数据，不重写表，秒级完成。VARCHAR(2000) 完全在这个安全区内。
--   - 老数据一行不动。字段 CHARACTER SET / COLLATE 沿用原来的（DEFAULT），不改。
--
-- 幂等说明：
--   - 重复对已经是 VARCHAR(2000) 的列执行 MODIFY COLUMN 也是空操作，不会报错。
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   mysql -h <host> -P 3306 -u <user> -p bigbear_fastpay < V1_5__widen_url_columns_mysql.sql
--
-- 怎么回退：见文件末尾的「回退脚本」注释块。**动手之前先读那段说明。**
-- =====================================================

ALTER TABLE fp_pay_order
    MODIFY COLUMN notify_url VARCHAR(2000) DEFAULT NULL COMMENT '回调通知地址（支付成功后通知商户系统的URL）';

ALTER TABLE fp_pay_order
    MODIFY COLUMN return_url VARCHAR(2000) DEFAULT NULL COMMENT '支付成功跳转地址（支付成功后用户浏览器跳转的URL）';

ALTER TABLE fp_merchant
    MODIFY COLUMN notify_url VARCHAR(2000) DEFAULT NULL COMMENT '支付成功回调地址（用户支付成功后，平台会将支付结果回推给商户系统）';

ALTER TABLE fp_merchant
    MODIFY COLUMN return_url VARCHAR(2000) DEFAULT NULL COMMENT '支付成功跳转地址（默认商户支付成功跳转地址，创建订单时传了returnUrl会以传入的为准）';

-- =====================================================
-- 回退脚本 —— 动手之前先读这一段
-- =====================================================
-- 【默认回退方式：只把 jar 退回上一个 tag，不要缩这四列。】
--   老版本 jar 对着 VARCHAR(2000) 的库能正常跑，退 jar 就够了。
--
-- 【不要在库里已经有 >255 长 URL 的时候缩回 255。】
--   MySQL 缩列时如果有一行现有数据超长，5.7 严格模式下会直接报错并终止；即使
--   在宽松模式下也会静默截断历史数据，把老订单的 URL 剪掉。
--
-- 【只有确定这个版本以后不再上、且库里从没写过 >255 的 URL，才在业务低峰缩回。】
--   -- 确认没有超长数据（四条都返回 0 才可以缩）：
--   SELECT COUNT(*) FROM fp_pay_order WHERE CHAR_LENGTH(notify_url) > 255;
--   SELECT COUNT(*) FROM fp_pay_order WHERE CHAR_LENGTH(return_url) > 255;
--   SELECT COUNT(*) FROM fp_merchant  WHERE CHAR_LENGTH(notify_url) > 255;
--   SELECT COUNT(*) FROM fp_merchant  WHERE CHAR_LENGTH(return_url) > 255;
--
--   -- 全部为 0 之后再执行：
--   ALTER TABLE fp_pay_order MODIFY COLUMN notify_url VARCHAR(255) DEFAULT NULL;
--   ALTER TABLE fp_pay_order MODIFY COLUMN return_url VARCHAR(255) DEFAULT NULL;
--   ALTER TABLE fp_merchant  MODIFY COLUMN notify_url VARCHAR(255) DEFAULT NULL;
--   ALTER TABLE fp_merchant  MODIFY COLUMN return_url VARCHAR(255) DEFAULT NULL;
