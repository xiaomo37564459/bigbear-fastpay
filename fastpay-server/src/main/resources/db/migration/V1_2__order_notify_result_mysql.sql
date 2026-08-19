-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.2（MySQL / MariaDB）—— 订单回调结果落库
-- 说明：老库升级用。新库直接执行 init.sql 就是新结构，不需要跑这里。
-- 变更点：
--   1. fp_pay_order 新增 notify_result 列 —— 存商户回调最近一次返回的内容（截断到 1000 字符）
--   2. fp_pay_order 新增 notify_error 列  —— 存商户回调最近一次的报错信息（超时/连不上/格式错等）
--
-- 为什么这么做：
--   现在回调成功失败都只写日志，运营在后台点开订单只看得到「回调失败」四个字，看不到为什么失败。
--   落库之后，管理后台和商户中心的订单详情就能直接看到失败原因，不用再去翻服务器日志。
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   mysql -h <host> -P 3306 -u <user> -p bigbear_fastpay < V1_2__order_notify_result_mysql.sql
--
-- 注意：本项目当前生产环境跑的是 PostgreSQL，MySQL 版仅为老装机或本地 MySQL 部署预留。
--
-- 怎么回退：见文件末尾的「回退脚本」注释块，把两行 ALTER TABLE 拷出来单独执行即可。
-- =====================================================

-- 新增：商户回调返回内容（截断到 1000 字符；商户回调可能返回长报错页面，避免撑爆行）
-- MySQL 8.0.29+ 支持 IF NOT EXISTS；老版本 MySQL 若报语法错，就去掉 IF NOT EXISTS 再执行
ALTER TABLE fp_pay_order
    ADD COLUMN IF NOT EXISTS notify_result VARCHAR(1000) DEFAULT NULL COMMENT '最近一次回调商户返回的内容（截断到 1000 字符）';

-- 新增：商户回调错误信息（异常类名 + message，截断到 500 字符）
ALTER TABLE fp_pay_order
    ADD COLUMN IF NOT EXISTS notify_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次回调失败的错误信息（超时/连不上/格式错等）';

-- =====================================================
-- 回退脚本（如需回退，把下面两行去掉注释后单独执行）
-- 会丢掉两列里已经积累的历史回调记录，其余订单数据不受影响。
-- =====================================================
-- ALTER TABLE fp_pay_order DROP COLUMN notify_result;
-- ALTER TABLE fp_pay_order DROP COLUMN notify_error;
