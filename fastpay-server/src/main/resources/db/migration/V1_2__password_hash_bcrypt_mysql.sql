-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.2（MySQL / MariaDB）—— 密码存法从 MD5 换成 bcrypt
-- 说明：老库升级用。新库直接执行 init.sql 就是新结构，不需要跑这里。
-- 变更点：
--   1. fp_admin.password    从 VARCHAR(64) 放宽到 VARCHAR(255)
--   2. fp_merchant.password 从 VARCHAR(64) 放宽到 VARCHAR(255)
-- 为什么要放宽：
--   - 新密码用 bcrypt 存储，一条哈希是 60 个字符，旧的 VARCHAR(64) 虽然刚好塞得下，但余量太小，
--     以后要再升级（比如换 Argon2、或加算法前缀）就会截断报错。一次放宽到 255 一劳永逸。
-- 兼容说明（不需要停机重置密码）：
--   - 只放宽字段，不动任何一行已有数据；库里遗留的 MD5 密码原样保留。
--   - 后端校验时新旧格式都认：老账号照常登录，登录成功后由后端把这条记录自动重算成 bcrypt 存回，
--     老格式随用户陆续登录自然消失，全程用户无感，没有人会被挡在门外。
--   - 所以本次升级只做「先跑本脚本、再重启后端」两步，和 V1.1 的发版节奏一致。
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   mysql -h <host> -P 3306 -u <user> -p bigbear_fastpay < V1_2__password_hash_bcrypt_mysql.sql
--
-- 注意：本项目当前生产环境跑的是 PostgreSQL，MySQL 版仅为老装机或本地 MySQL 部署预留。
-- =====================================================

-- 放宽管理员密码字段，容纳 bcrypt（及未来更长的算法）
ALTER TABLE fp_admin
    MODIFY COLUMN password VARCHAR(255) NOT NULL COMMENT '密码（bcrypt 加密存储；兼容历史 MD5，登录成功后自动升级为 bcrypt）';

-- 放宽商户密码字段，和管理员保持同一套存法
ALTER TABLE fp_merchant
    MODIFY COLUMN password VARCHAR(255) NOT NULL COMMENT '登录密码（bcrypt 加密存储；兼容历史 MD5，登录成功后自动升级为 bcrypt）';
