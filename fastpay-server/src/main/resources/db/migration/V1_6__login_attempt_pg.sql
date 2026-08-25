-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.6（PostgreSQL）—— 登录失败次数记录表
-- 说明：老库升级用。新库直接执行 init-pg.sql 就是新结构，不需要跑这里。
--
-- 背景（MTM-162）：
--   - 管理后台和商户后台登录页以前不做任何限次，任何人写个脚本挂在那儿慢慢试都不会被拦。
--   - 这个脚本创建一张 fp_login_attempt 表，供后端 LoginLimitService 存放
--     「同一账号 / 同一 IP 连续失败几次、什么时候锁到什么时候」的状态。
--   - 存到数据库是刻意的：服务重启后失败次数不能清零，否则攻击者只要触发一次重启就白干。
--
-- 变更点：
--   - 新增表 fp_login_attempt
--   - 唯一索引 uk_scope_identity 保证「同一作用域下同一把钥匙只有一行」
--   - 二级索引 idx_scope_locked_until 供后续「哪些账号还在锁定中」的巡检查询
--
-- 兼容说明：
--   - 只新增表，不动任何现有表和数据；新老 jar 都能跑（老 jar 忽略这张表）。
--   - 后端 LoginLimitService 是懒加载：表不存在时后端能正常启动，但一有人登录就会 500 —— 所以先跑脚本再上新 jar。
--
-- 幂等说明：
--   - CREATE TABLE IF NOT EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS 均可重复执行。
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   psql -h <host> -p 5432 -U <user> -d bigbear_fastpay -f V1_6__login_attempt_pg.sql
-- =====================================================

BEGIN;

CREATE TABLE IF NOT EXISTS fp_login_attempt (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(20) NOT NULL,
    identity_key VARCHAR(200) NOT NULL,
    key_type VARCHAR(10) NOT NULL,
    fail_count INT NOT NULL DEFAULT 0,
    first_failed_at TIMESTAMP DEFAULT NULL,
    last_failed_at TIMESTAMP DEFAULT NULL,
    locked_until TIMESTAMP DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_login_attempt_scope_identity
    ON fp_login_attempt (scope, identity_key);

CREATE INDEX IF NOT EXISTS idx_login_attempt_scope_locked_until
    ON fp_login_attempt (scope, locked_until);

COMMENT ON TABLE fp_login_attempt IS '登录失败次数记录 - MTM-162 登录限次的持久化状态';
COMMENT ON COLUMN fp_login_attempt.scope IS '作用域：admin-管理后台，merchant-商户后台';
COMMENT ON COLUMN fp_login_attempt.identity_key IS '钥匙标识：账号名归一化（trim+lowercase），或 "ip:" 前缀 + IP';
COMMENT ON COLUMN fp_login_attempt.key_type IS '钥匙类型：user-账号维度，ip-IP维度';
COMMENT ON COLUMN fp_login_attempt.fail_count IS '当前失败累计次数；成功登录后清零';
COMMENT ON COLUMN fp_login_attempt.first_failed_at IS '本次统计窗口第一次失败时间（滑动窗口重置判断用）';
COMMENT ON COLUMN fp_login_attempt.last_failed_at IS '最近一次失败时间';
COMMENT ON COLUMN fp_login_attempt.locked_until IS '锁定截止时间；为 NULL 或 <= now 视为未锁';

COMMIT;

-- =====================================================
-- 回退脚本 —— 动手之前先读这一段
-- =====================================================
-- 【默认回退方式：只把 jar 退回上一个 tag，不要删表。】
--   老版本 jar 完全忽略这张表，留着它不影响任何功能，也不占什么空间。
--   删表反而会把已经在锁定中的攻击窗口一起丢掉。
--
-- 【确认这张表以后不用了，才在业务低峰删。】
--   DROP TABLE IF EXISTS fp_login_attempt;
