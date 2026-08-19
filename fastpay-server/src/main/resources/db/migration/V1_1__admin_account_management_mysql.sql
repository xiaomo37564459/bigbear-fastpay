-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.1（MySQL / MariaDB）—— 管理员账号密码可自助修改
-- 说明：老库升级用。新库直接执行 init.sql 就是新结构，不需要跑这里。
-- 变更点：
--   1. fp_admin.username 长度从 VARCHAR(50) 放宽到 VARCHAR(100)，能存邮箱格式的账号
--   2. fp_admin 新增 token_version 列，用于让"改密码/改账号"后的旧登录令牌立即失效
-- 兼容说明：
--   - 已经在库里的老登录令牌里没有 tokenVersion 字段，认证拦截器会把它当作 0 处理，
--     与 token_version 默认值 0 一致，所以升级过程不会把在线用户强制踢下线；
--   - 只有第一次改密码/改账号之后，这个人手上的老令牌才失效，符合"改完密码原凭证要失效"的验收。
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   mysql -h <host> -P 3306 -u <user> -p bigbear_fastpay < V1_1__admin_account_management_mysql.sql
--
-- 注意：本项目当前生产环境跑的是 PostgreSQL，MySQL 版仅为老装机或本地 MySQL 部署预留。
-- =====================================================

-- 放宽用户名字段长度，兼容邮箱格式（例如 name@example.com）
ALTER TABLE fp_admin MODIFY COLUMN username VARCHAR(100) NOT NULL COMMENT '用户名（登录账号，支持普通字符串或邮箱格式）';

-- 新增令牌版本号列，默认 0；升级前签发的老令牌里也没有这个字段，按 0 匹配，不会误踢
-- MySQL 8.0.29+ 支持 IF NOT EXISTS；老版本 MySQL 若报语法错，就去掉 IF NOT EXISTS 再执行
ALTER TABLE fp_admin ADD COLUMN IF NOT EXISTS token_version INT DEFAULT 0 COMMENT '令牌版本号（每次改密码/改账号 +1，用于让旧登录令牌失效）';

-- 保险：把老数据里 token_version 是 NULL 的补成 0
UPDATE fp_admin SET token_version = 0 WHERE token_version IS NULL;
