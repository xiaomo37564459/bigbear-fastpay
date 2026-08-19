-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.1（管理员账号密码可自助修改）
-- 说明：老库升级用；新库直接执行 init.sql / init-pg.sql 就是新结构，不需要跑这里。
-- 变更点：
--   1. fp_admin.username 长度从 VARCHAR(50) 放宽到 VARCHAR(100)，能存邮箱格式的账号
--   2. fp_admin 新增 token_version 列，用于让"改密码/改账号"后的旧登录令牌立即失效
-- 兼容说明：
--   - 已经在库里的老登录令牌里没有 tokenVersion 字段，认证拦截器会把它当作 0 处理，
--     与 token_version 默认值 0 一致，所以升级过程不会把在线用户强制踢下线；
--   - 只有第一次改密码/改账号之后，这个人手上的老令牌才失效，符合"改完密码原凭证要失效"的验收。
-- 数据库方言注意：两个 ALTER 都用了两个方言都支持的最简写法，不需要根据 MySQL / PG 分开维护。
-- =====================================================

-- 放宽用户名字段长度，兼容邮箱格式（例如 37564459@qq.com）
ALTER TABLE fp_admin ALTER COLUMN username TYPE VARCHAR(100);  -- PostgreSQL
-- ALTER TABLE fp_admin MODIFY COLUMN username VARCHAR(100) NOT NULL;  -- MySQL 用这一行，把上面那行注释掉

-- 新增令牌版本号列，默认 0；升级前签发的老令牌里也没有这个字段，按 0 匹配，不会误踢
ALTER TABLE fp_admin ADD COLUMN token_version INT DEFAULT 0;
