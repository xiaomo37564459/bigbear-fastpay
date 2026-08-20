-- =====================================================
-- Fast 易支付 数据库初始化脚本（PostgreSQL 版）
-- 版本：1.0.0
-- 说明：本脚本是 init.sql（MySQL 版）的 PostgreSQL 等价版本，两边字段、索引、
--       初始数据完全对应，功能一致。
--
-- 使用方法：
--   1. PostgreSQL 不支持在脚本里 CREATE DATABASE IF NOT EXISTS + USE，
--      需要先手动建好目标数据库（数据库名建议用 bigbear_fastpay，和 MySQL 版一致）：
--        createdb bigbear_fastpay
--      或者：
--        CREATE DATABASE bigbear_fastpay;
--   2. 再连到这个库执行本脚本：
--        psql -h <host> -p <port> -U <user> -d bigbear_fastpay -f init-pg.sql
-- =====================================================

-- =====================================================
-- 1. 管理员表 (fp_admin)
-- 说明：存储系统管理员账号信息
-- =====================================================
DROP TABLE IF EXISTS fp_admin;
CREATE TABLE fp_admin (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(64) NOT NULL,
    nickname VARCHAR(50) DEFAULT NULL,
    avatar VARCHAR(255) DEFAULT NULL,
    status SMALLINT DEFAULT 1,
    token_version INT DEFAULT 0,
    last_login_time TIMESTAMP DEFAULT NULL,
    last_login_ip VARCHAR(50) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- 更新时间没有用数据库触发器实现"改数据自动更新时间"，程序层 MybatisPlusConfig 已经在插入/更新时自动填充，这里只保留默认值
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_admin_username UNIQUE (username)
);

COMMENT ON TABLE fp_admin IS '管理员表 - 存储系统管理员账号信息';
COMMENT ON COLUMN fp_admin.id IS '主键ID';
COMMENT ON COLUMN fp_admin.username IS '用户名（登录账号，支持普通字符串或邮箱格式）';
COMMENT ON COLUMN fp_admin.password IS '密码（MD5加密存储）';
COMMENT ON COLUMN fp_admin.nickname IS '昵称（显示名称）';
COMMENT ON COLUMN fp_admin.avatar IS '头像URL地址';
COMMENT ON COLUMN fp_admin.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN fp_admin.token_version IS '令牌版本号（每次改密码/改账号 +1，用于让旧登录令牌失效）';
COMMENT ON COLUMN fp_admin.last_login_time IS '最后登录时间';
COMMENT ON COLUMN fp_admin.last_login_ip IS '最后登录IP地址';
COMMENT ON COLUMN fp_admin.create_time IS '创建时间';
COMMENT ON COLUMN fp_admin.update_time IS '更新时间';
COMMENT ON COLUMN fp_admin.deleted IS '逻辑删除标记：0-未删除，1-已删除';

-- =====================================================
-- 2. 商户表 (fp_merchant)
-- 说明：存储商户（有个人收款需求的商家）信息
-- =====================================================
DROP TABLE IF EXISTS fp_merchant;
CREATE TABLE fp_merchant (
    id BIGSERIAL PRIMARY KEY,
    merchant_no VARCHAR(32) NOT NULL,
    merchant_name VARCHAR(100) NOT NULL,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(64) NOT NULL,
    contact_name VARCHAR(50) DEFAULT NULL,
    contact_phone VARCHAR(20) DEFAULT NULL,
    contact_email VARCHAR(100) DEFAULT NULL,
    api_key VARCHAR(64) NOT NULL,
    api_secret VARCHAR(64) NOT NULL,
    notify_url VARCHAR(2000) DEFAULT NULL,
    return_url VARCHAR(2000) DEFAULT NULL,
    total_amount DECIMAL(12,2) DEFAULT 0.00,
    total_count INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    remark VARCHAR(500) DEFAULT NULL,
    last_login_time TIMESTAMP DEFAULT NULL,
    last_login_ip VARCHAR(50) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_merchant_no UNIQUE (merchant_no),
    CONSTRAINT uk_merchant_username UNIQUE (username)
);

COMMENT ON TABLE fp_merchant IS '商户表 - 存储商户（有个人收款需求的商家）信息';
COMMENT ON COLUMN fp_merchant.id IS '主键ID';
COMMENT ON COLUMN fp_merchant.merchant_no IS '商户编号（唯一标识，用于API调用）';
COMMENT ON COLUMN fp_merchant.merchant_name IS '商户名称';
COMMENT ON COLUMN fp_merchant.username IS '登录用户名';
COMMENT ON COLUMN fp_merchant.password IS '登录密码（MD5加密存储）';
COMMENT ON COLUMN fp_merchant.contact_name IS '联系人姓名';
COMMENT ON COLUMN fp_merchant.contact_phone IS '联系电话';
COMMENT ON COLUMN fp_merchant.contact_email IS '联系邮箱';
COMMENT ON COLUMN fp_merchant.api_key IS 'API密钥（用于接口调用身份识别）';
COMMENT ON COLUMN fp_merchant.api_secret IS 'API密钥Secret（用于接口签名验证）';
COMMENT ON COLUMN fp_merchant.notify_url IS '支付成功回调地址（用户支付成功后，平台会将支付结果回推给商户系统）';
COMMENT ON COLUMN fp_merchant.return_url IS '支付成功跳转地址（默认商户支付成功跳转地址，创建订单时传了returnUrl会以传入的为准）';
COMMENT ON COLUMN fp_merchant.total_amount IS '累计交易金额（单位：元）';
COMMENT ON COLUMN fp_merchant.total_count IS '累计交易笔数';
COMMENT ON COLUMN fp_merchant.status IS '状态：0-禁用，1-启用，2-待审核';
COMMENT ON COLUMN fp_merchant.remark IS '备注信息';
COMMENT ON COLUMN fp_merchant.last_login_time IS '最后登录时间';
COMMENT ON COLUMN fp_merchant.last_login_ip IS '最后登录IP地址';
COMMENT ON COLUMN fp_merchant.create_time IS '创建时间';
COMMENT ON COLUMN fp_merchant.update_time IS '更新时间';
COMMENT ON COLUMN fp_merchant.deleted IS '逻辑删除标记：0-未删除，1-已删除';

-- =====================================================
-- 3. 店铺表 (fp_shop)
-- 说明：商户可以创建多个店铺，每个店铺维护自己的收款二维码
-- =====================================================
DROP TABLE IF EXISTS fp_shop;
CREATE TABLE fp_shop (
    id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    shop_no VARCHAR(32) NOT NULL,
    shop_name VARCHAR(100) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    contact_name VARCHAR(50) DEFAULT NULL,
    contact_phone VARCHAR(20) DEFAULT NULL,
    total_amount DECIMAL(12,2) DEFAULT 0.00,
    total_count INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_shop_no UNIQUE (shop_no)
);

CREATE INDEX idx_shop_merchant_id ON fp_shop (merchant_id);

COMMENT ON TABLE fp_shop IS '店铺表 - 商户可以创建多个店铺，每个店铺维护自己的收款二维码';
COMMENT ON COLUMN fp_shop.id IS '主键ID';
COMMENT ON COLUMN fp_shop.merchant_id IS '所属商户ID（关联fp_merchant.id）';
COMMENT ON COLUMN fp_shop.shop_no IS '店铺编号（唯一标识）';
COMMENT ON COLUMN fp_shop.shop_name IS '店铺名称';
COMMENT ON COLUMN fp_shop.description IS '店铺描述';
COMMENT ON COLUMN fp_shop.contact_name IS '联系人姓名';
COMMENT ON COLUMN fp_shop.contact_phone IS '联系电话';
COMMENT ON COLUMN fp_shop.total_amount IS '累计交易金额（单位：元）';
COMMENT ON COLUMN fp_shop.total_count IS '累计交易笔数';
COMMENT ON COLUMN fp_shop.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN fp_shop.create_time IS '创建时间';
COMMENT ON COLUMN fp_shop.update_time IS '更新时间';
COMMENT ON COLUMN fp_shop.deleted IS '逻辑删除标记：0-未删除，1-已删除';

-- =====================================================
-- 4. 商户通道配置表 (fp_merchant_channel)
-- 说明：存储商户的支付通道配置
--       通道指的是：支付宝、微信、第三方聚合支付平台
-- =====================================================
DROP TABLE IF EXISTS fp_merchant_channel;
CREATE TABLE fp_merchant_channel (
    id BIGSERIAL PRIMARY KEY,
    channel_name VARCHAR(100) NOT NULL,
    merchant_id BIGINT NOT NULL,
    pay_type VARCHAR(20) NOT NULL,
    status SMALLINT DEFAULT 1,
    template TEXT,
    remark VARCHAR(500) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

CREATE INDEX idx_channel_merchant_id ON fp_merchant_channel (merchant_id);
CREATE INDEX idx_channel_pay_type ON fp_merchant_channel (pay_type);

COMMENT ON TABLE fp_merchant_channel IS '商户通道配置表 - 存储商户的支付通道（支付宝、微信等）配置';
COMMENT ON COLUMN fp_merchant_channel.id IS '通道ID（主键）';
COMMENT ON COLUMN fp_merchant_channel.channel_name IS '通道名称';
COMMENT ON COLUMN fp_merchant_channel.merchant_id IS '通道所属商户ID（关联fp_merchant.id）';
COMMENT ON COLUMN fp_merchant_channel.pay_type IS '支付类型：wxpay-微信支付，alipay-支付宝';
COMMENT ON COLUMN fp_merchant_channel.status IS '通道启用状态：0-禁用，1-启用';
COMMENT ON COLUMN fp_merchant_channel.template IS '通道消息模版（监听软件监听到支付成功信息后，会替换模版占位符内容回调NotifyController.callback）';
COMMENT ON COLUMN fp_merchant_channel.remark IS '备注信息';
COMMENT ON COLUMN fp_merchant_channel.create_time IS '创建时间';
COMMENT ON COLUMN fp_merchant_channel.update_time IS '更新时间';
COMMENT ON COLUMN fp_merchant_channel.deleted IS '逻辑删除标记：0-未删除，1-已删除';

-- =====================================================
-- 5. 收款二维码表 (fp_pay_qrcode)
-- 说明：存储商户上传的个人收款二维码信息
-- =====================================================
DROP TABLE IF EXISTS fp_pay_qrcode;
CREATE TABLE fp_pay_qrcode (
    id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    channel_id VARCHAR(32) DEFAULT NULL,
    shop_id BIGINT NOT NULL,
    pay_type VARCHAR(20) NOT NULL,
    qrcode_name VARCHAR(100) DEFAULT NULL,
    qrcode_url TEXT,
    total_amount DECIMAL(12,2) DEFAULT 0.00,
    total_count INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    sort_weight INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

CREATE INDEX idx_qrcode_merchant_id ON fp_pay_qrcode (merchant_id);
CREATE INDEX idx_qrcode_channel_id ON fp_pay_qrcode (channel_id);
CREATE INDEX idx_qrcode_shop_id ON fp_pay_qrcode (shop_id);
CREATE INDEX idx_qrcode_pay_type ON fp_pay_qrcode (pay_type);
CREATE INDEX idx_qrcode_status_weight ON fp_pay_qrcode (status, sort_weight);

COMMENT ON TABLE fp_pay_qrcode IS '收款二维码表 - 存储商户上传的个人收款二维码信息';
COMMENT ON COLUMN fp_pay_qrcode.id IS '主键ID';
COMMENT ON COLUMN fp_pay_qrcode.merchant_id IS '所属商户ID（关联fp_merchant.id）';
COMMENT ON COLUMN fp_pay_qrcode.channel_id IS '配置的通道ID（关联fp_merchant_channel.id）';
COMMENT ON COLUMN fp_pay_qrcode.shop_id IS '所属店铺ID（关联fp_shop.id）';
COMMENT ON COLUMN fp_pay_qrcode.pay_type IS '支付类型（冗余字段，等于商户通道里的payType）：wxpay-微信支付，alipay-支付宝';
COMMENT ON COLUMN fp_pay_qrcode.qrcode_name IS '二维码名称/备注';
COMMENT ON COLUMN fp_pay_qrcode.qrcode_url IS '二维码内容URL（解析后的支付链接）';
COMMENT ON COLUMN fp_pay_qrcode.total_amount IS '累计收款金额（单位：元）';
COMMENT ON COLUMN fp_pay_qrcode.total_count IS '累计收款笔数';
COMMENT ON COLUMN fp_pay_qrcode.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN fp_pay_qrcode.sort_weight IS '排序权重（越大越优先使用）';
COMMENT ON COLUMN fp_pay_qrcode.create_time IS '创建时间';
COMMENT ON COLUMN fp_pay_qrcode.update_time IS '更新时间';
COMMENT ON COLUMN fp_pay_qrcode.deleted IS '逻辑删除标记：0-未删除，1-已删除';

-- =====================================================
-- 6. 支付订单表 (fp_pay_order)
-- 说明：存储所有支付订单信息
-- =====================================================
DROP TABLE IF EXISTS fp_pay_order;
CREATE TABLE fp_pay_order (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL,
    out_trade_no VARCHAR(64) NOT NULL,
    merchant_id BIGINT NOT NULL,
    shop_id BIGINT DEFAULT NULL,
    qrcode_id BIGINT DEFAULT NULL,
    pay_type VARCHAR(20) NOT NULL,
    pay_method VARCHAR(20) DEFAULT 'page',
    amount DECIMAL(10,2) NOT NULL,
    pay_amount DECIMAL(10,2) DEFAULT NULL,
    subject VARCHAR(200) NOT NULL,
    status SMALLINT DEFAULT 0,
    notify_url VARCHAR(2000) DEFAULT NULL,
    return_url VARCHAR(2000) DEFAULT NULL,
    notify_status SMALLINT DEFAULT 0,
    notify_count INT DEFAULT 0,
    last_notify_time TIMESTAMP DEFAULT NULL,
    notify_result VARCHAR(1000) DEFAULT NULL,
    notify_error VARCHAR(500) DEFAULT NULL,
    pay_time TIMESTAMP DEFAULT NULL,
    expire_time TIMESTAMP DEFAULT NULL,
    client_ip VARCHAR(50) DEFAULT NULL,
    ext_param TEXT,
    order_source VARCHAR(20) NOT NULL DEFAULT 'native',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_no UNIQUE (order_no)
);

CREATE INDEX idx_order_merchant_out_trade ON fp_pay_order (merchant_id, out_trade_no);
CREATE INDEX idx_order_merchant_id ON fp_pay_order (merchant_id);
CREATE INDEX idx_order_shop_id ON fp_pay_order (shop_id);
CREATE INDEX idx_order_status ON fp_pay_order (status);
CREATE INDEX idx_order_notify_status ON fp_pay_order (notify_status);
CREATE INDEX idx_order_create_time ON fp_pay_order (create_time);
CREATE INDEX idx_order_pay_time ON fp_pay_order (pay_time);

COMMENT ON TABLE fp_pay_order IS '支付订单表 - 存储所有支付订单信息';
COMMENT ON COLUMN fp_pay_order.id IS '主键ID';
COMMENT ON COLUMN fp_pay_order.order_no IS '平台订单号（系统生成的唯一订单号）';
COMMENT ON COLUMN fp_pay_order.out_trade_no IS '商户订单号（商户系统的订单号）';
COMMENT ON COLUMN fp_pay_order.merchant_id IS '商户ID（关联fp_merchant.id）';
COMMENT ON COLUMN fp_pay_order.shop_id IS '店铺ID（关联fp_shop.id）';
COMMENT ON COLUMN fp_pay_order.qrcode_id IS '使用的二维码ID（关联fp_pay_qrcode.id）';
COMMENT ON COLUMN fp_pay_order.pay_type IS '支付类型：wxpay-微信支付，alipay-支付宝';
COMMENT ON COLUMN fp_pay_order.pay_method IS '支付方式：page-页面跳转支付，api-接口调用支付';
COMMENT ON COLUMN fp_pay_order.amount IS '订单金额（单位：元）';
COMMENT ON COLUMN fp_pay_order.pay_amount IS '实际支付金额（单位：元）';
COMMENT ON COLUMN fp_pay_order.subject IS '商品名称/订单描述';
COMMENT ON COLUMN fp_pay_order.status IS '订单状态：0-待支付，1-已支付，2-已过期，3-已关闭';
COMMENT ON COLUMN fp_pay_order.notify_url IS '回调通知地址（支付成功后通知商户系统的URL）';
COMMENT ON COLUMN fp_pay_order.return_url IS '支付成功跳转地址（支付成功后用户浏览器跳转的URL）';
COMMENT ON COLUMN fp_pay_order.notify_status IS '回调通知状态：0-未通知，1-通知成功，2-通知失败';
COMMENT ON COLUMN fp_pay_order.notify_count IS '回调通知次数（已尝试通知的次数）';
COMMENT ON COLUMN fp_pay_order.last_notify_time IS '最后通知时间';
COMMENT ON COLUMN fp_pay_order.notify_result IS '最近一次回调商户返回的内容（截断到 1000 字符）';
COMMENT ON COLUMN fp_pay_order.notify_error IS '最近一次回调失败的错误信息（超时/连不上/格式错等）';
COMMENT ON COLUMN fp_pay_order.pay_time IS '支付时间（用户完成支付的时间）';
COMMENT ON COLUMN fp_pay_order.expire_time IS '过期时间（订单超过此时间未支付将自动关闭）';
COMMENT ON COLUMN fp_pay_order.client_ip IS '客户端IP地址（发起支付请求的客户端IP）';
COMMENT ON COLUMN fp_pay_order.ext_param IS '扩展参数（JSON格式，用于存储额外信息）';
COMMENT ON COLUMN fp_pay_order.order_source IS '订单来源：native-原生FastPay接口，epay-彩虹易支付协议接口；回调时按来源发对应格式';
COMMENT ON COLUMN fp_pay_order.create_time IS '创建时间';
COMMENT ON COLUMN fp_pay_order.update_time IS '更新时间';

-- =====================================================
-- 初始化数据
-- =====================================================
--
-- 说明：不再在此脚本里预置默认管理员账号（曾经的 admin / 123456 已经在公开仓库里
-- 被搜到过，属于人尽皆知的默认凭证，见 MTM-149 问题一）。
-- 管理员账号改由后端 InitConfig 在应用启动时按下面这套规则来准备：
--   1. 如果 fp_admin 表里已经有账号，什么都不做；
--   2. 表为空时：
--      · fastpay.admin.username / fastpay.admin.password 都显式配了 → 用这一对；
--      · 只配了用户名、没配密码（例如生产环境）→ 随机生成 16 位密码，明文打到 warn 日志里，
--        运维在 systemd/journalctl 里拿一次、立刻登进后台改成自己的密码，然后就不能再拿到了。
-- dev/测试用的默认凭证放在 application-dev.yml 里，不进这里。

-- =====================================================
-- 表结构说明
-- =====================================================
--
-- 1. fp_admin        - 管理员表，存储后台管理员账号
-- 2. fp_merchant     - 商户表，存储接入平台的商户信息
-- 3. fp_shop         - 店铺表，商户下可创建多个店铺
-- 4. fp_merchant_channel - 商户通道表，配置商户的支付通道
-- 5. fp_pay_qrcode   - 收款二维码表，存储商户上传的收款码
-- 6. fp_pay_order    - 支付订单表，存储所有支付订单记录
--
-- 关系说明：
-- - 一个商户(merchant)可以有多个店铺(shop)
-- - 一个商户(merchant)可以配置多个通道(channel)
-- - 一个店铺(shop)可以有多个二维码(qrcode)
-- - 一个订单(order)关联一个商户、一个店铺、一个二维码
-- =====================================================
