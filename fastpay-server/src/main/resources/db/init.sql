-- =====================================================
-- Fast 易支付 数据库初始化脚本
-- 版本：1.0.0
-- 说明：本脚本用于初始化 Fast 易支付系统的数据库表结构
-- =====================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS bigbear_fastpay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE bigbear_fastpay;

-- =====================================================
-- 1. 管理员表 (fp_admin)
-- 说明：存储系统管理员账号信息
-- =====================================================
DROP TABLE IF EXISTS `fp_admin`;
CREATE TABLE `fp_admin` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(100) NOT NULL COMMENT '用户名（登录账号，支持普通字符串或邮箱格式）',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（bcrypt 加密存储；兼容历史 MD5，登录成功后自动升级为 bcrypt）',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称（显示名称）',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL地址',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `token_version` INT DEFAULT 0 COMMENT '令牌版本号（每次改密码/改账号 +1，用于让旧登录令牌失效）',
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP地址',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`) COMMENT '用户名唯一索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员表 - 存储系统管理员账号信息';

-- =====================================================
-- 2. 商户表 (fp_merchant)
-- 说明：存储商户（有个人收款需求的商家）信息
-- =====================================================
DROP TABLE IF EXISTS `fp_merchant`;
CREATE TABLE `fp_merchant` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `merchant_no` VARCHAR(32) NOT NULL COMMENT '商户编号（唯一标识，用于API调用）',
    `merchant_name` VARCHAR(100) NOT NULL COMMENT '商户名称',
    `username` VARCHAR(50) NOT NULL COMMENT '登录用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '登录密码（bcrypt 加密存储；兼容历史 MD5，登录成功后自动升级为 bcrypt）',
    `contact_name` VARCHAR(50) DEFAULT NULL COMMENT '联系人姓名',
    `contact_phone` VARCHAR(20) DEFAULT NULL COMMENT '联系电话',
    `contact_email` VARCHAR(100) DEFAULT NULL COMMENT '联系邮箱',
    `api_key` VARCHAR(64) NOT NULL COMMENT 'API密钥（用于接口调用身份识别）',
    `api_secret` VARCHAR(64) NOT NULL COMMENT 'API密钥Secret（用于接口签名验证）',
    `notify_url` VARCHAR(2000) DEFAULT NULL COMMENT '支付成功回调地址（用户支付成功后，平台会将支付结果回推给商户系统）',
    `return_url` VARCHAR(2000) DEFAULT NULL COMMENT '支付成功跳转地址（默认商户支付成功跳转地址，创建订单时传了returnUrl会以传入的为准）',
    `total_amount` DECIMAL(12,2) DEFAULT 0.00 COMMENT '累计交易金额（单位：元）',
    `total_count` INT DEFAULT 0 COMMENT '累计交易笔数',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用，2-待审核',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注信息',
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP地址',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_merchant_no` (`merchant_no`) COMMENT '商户编号唯一索引',
    UNIQUE KEY `uk_username` (`username`) COMMENT '用户名唯一索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户表 - 存储商户（有个人收款需求的商家）信息';

-- =====================================================
-- 3. 店铺表 (fp_shop)
-- 说明：商户可以创建多个店铺，每个店铺维护自己的收款二维码
-- =====================================================
DROP TABLE IF EXISTS `fp_shop`;
CREATE TABLE `fp_shop` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID（关联fp_merchant.id）',
    `shop_no` VARCHAR(32) NOT NULL COMMENT '店铺编号（唯一标识）',
    `shop_name` VARCHAR(100) NOT NULL COMMENT '店铺名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '店铺描述',
    `contact_name` VARCHAR(50) DEFAULT NULL COMMENT '联系人姓名',
    `contact_phone` VARCHAR(20) DEFAULT NULL COMMENT '联系电话',
    `total_amount` DECIMAL(12,2) DEFAULT 0.00 COMMENT '累计交易金额（单位：元）',
    `total_count` INT DEFAULT 0 COMMENT '累计交易笔数',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shop_no` (`shop_no`) COMMENT '店铺编号唯一索引',
    KEY `idx_merchant_id` (`merchant_id`) COMMENT '商户ID索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表 - 商户可以创建多个店铺，每个店铺维护自己的收款二维码';

-- =====================================================
-- 4. 商户通道配置表 (fp_merchant_channel)
-- 说明：存储商户的支付通道配置
--       通道指的是：支付宝、微信、第三方聚合支付平台
-- =====================================================
DROP TABLE IF EXISTS `fp_merchant_channel`;
CREATE TABLE `fp_merchant_channel` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '通道ID（主键）',
    `channel_name` VARCHAR(100) NOT NULL COMMENT '通道名称',
    `merchant_id` BIGINT NOT NULL COMMENT '通道所属商户ID（关联fp_merchant.id）',
    `pay_type` VARCHAR(20) NOT NULL COMMENT '支付类型：wxpay-微信支付，alipay-支付宝',
    `status` TINYINT DEFAULT 1 COMMENT '通道启用状态：0-禁用，1-启用',
    `template` TEXT COMMENT '通道消息模版（监听软件监听到支付成功信息后，会替换模版占位符内容回调NotifyController.callback）',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注信息',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_merchant_id` (`merchant_id`) COMMENT '商户ID索引',
    KEY `idx_pay_type` (`pay_type`) COMMENT '支付类型索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户通道配置表 - 存储商户的支付通道（支付宝、微信等）配置';

-- =====================================================
-- 5. 收款二维码表 (fp_pay_qrcode)
-- 说明：存储商户上传的个人收款二维码信息
-- =====================================================
DROP TABLE IF EXISTS `fp_pay_qrcode`;
CREATE TABLE `fp_pay_qrcode` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `merchant_id` BIGINT NOT NULL COMMENT '所属商户ID（关联fp_merchant.id）',
    `channel_id` VARCHAR(32) DEFAULT NULL COMMENT '配置的通道ID（关联fp_merchant_channel.id）',
    `shop_id` BIGINT NOT NULL COMMENT '所属店铺ID（关联fp_shop.id）',
    `pay_type` VARCHAR(20) NOT NULL COMMENT '支付类型（冗余字段，等于商户通道里的payType）：wxpay-微信支付，alipay-支付宝',
    `qrcode_name` VARCHAR(100) DEFAULT NULL COMMENT '二维码名称/备注',
    `qrcode_url` TEXT COMMENT '二维码内容URL（解析后的支付链接）',
    `total_amount` DECIMAL(12,2) DEFAULT 0.00 COMMENT '累计收款金额（单位：元）',
    `total_count` INT DEFAULT 0 COMMENT '累计收款笔数',
    `status` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `sort_weight` INT DEFAULT 0 COMMENT '排序权重（越大越优先使用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_merchant_id` (`merchant_id`) COMMENT '商户ID索引',
    KEY `idx_channel_id` (`channel_id`) COMMENT '通道ID索引',
    KEY `idx_shop_id` (`shop_id`) COMMENT '店铺ID索引',
    KEY `idx_pay_type` (`pay_type`) COMMENT '支付类型索引',
    KEY `idx_status_weight` (`status`, `sort_weight`) COMMENT '状态和权重复合索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款二维码表 - 存储商户上传的个人收款二维码信息';

-- =====================================================
-- 6. 支付订单表 (fp_pay_order)
-- 说明：存储所有支付订单信息
-- =====================================================
DROP TABLE IF EXISTS `fp_pay_order`;
CREATE TABLE `fp_pay_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_no` VARCHAR(32) NOT NULL COMMENT '平台订单号（系统生成的唯一订单号）',
    `out_trade_no` VARCHAR(64) NOT NULL COMMENT '商户订单号（商户系统的订单号）',
    `merchant_id` BIGINT NOT NULL COMMENT '商户ID（关联fp_merchant.id）',
    `shop_id` BIGINT DEFAULT NULL COMMENT '店铺ID（关联fp_shop.id）',
    `qrcode_id` BIGINT DEFAULT NULL COMMENT '使用的二维码ID（关联fp_pay_qrcode.id）',
    `pay_type` VARCHAR(20) NOT NULL COMMENT '支付类型：wxpay-微信支付，alipay-支付宝',
    `pay_method` VARCHAR(20) DEFAULT 'page' COMMENT '支付方式：page-页面跳转支付，api-接口调用支付',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '订单金额（单位：元）',
    `pay_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '实际支付金额（单位：元）',
    `subject` VARCHAR(200) NOT NULL COMMENT '商品名称/订单描述',
    `status` TINYINT DEFAULT 0 COMMENT '订单状态：0-待支付，1-已支付，2-已过期，3-已关闭',
    `notify_url` VARCHAR(2000) DEFAULT NULL COMMENT '回调通知地址（支付成功后通知商户系统的URL）',
    `return_url` VARCHAR(2000) DEFAULT NULL COMMENT '支付成功跳转地址（支付成功后用户浏览器跳转的URL）',
    `notify_status` TINYINT DEFAULT 0 COMMENT '回调通知状态：0-未通知，1-通知成功，2-通知失败',
    `notify_count` INT DEFAULT 0 COMMENT '回调通知次数（已尝试通知的次数）',
    `last_notify_time` DATETIME DEFAULT NULL COMMENT '最后通知时间',
    `notify_result` VARCHAR(1000) DEFAULT NULL COMMENT '最近一次回调商户返回的内容（截断到 1000 字符）',
    `notify_error` VARCHAR(500) DEFAULT NULL COMMENT '最近一次回调失败的错误信息（超时/连不上/格式错等）',
    `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间（用户完成支付的时间）',
    `expire_time` DATETIME DEFAULT NULL COMMENT '过期时间（订单超过此时间未支付将自动关闭）',
    `client_ip` VARCHAR(50) DEFAULT NULL COMMENT '客户端IP地址（发起支付请求的客户端IP）',
    `ext_param` TEXT COMMENT '扩展参数（JSON格式，用于存储额外信息）',
    `order_source` VARCHAR(20) NOT NULL DEFAULT 'native' COMMENT '订单来源：native-原生FastPay接口，epay-彩虹易支付协议接口；回调时按来源发对应格式',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`) COMMENT '平台订单号唯一索引',
    KEY `idx_merchant_out_trade` (`merchant_id`, `out_trade_no`) COMMENT '商户ID和商户订单号复合索引',
    KEY `idx_merchant_id` (`merchant_id`) COMMENT '商户ID索引',
    KEY `idx_shop_id` (`shop_id`) COMMENT '店铺ID索引',
    KEY `idx_status` (`status`) COMMENT '订单状态索引',
    KEY `idx_notify_status` (`notify_status`) COMMENT '通知状态索引',
    KEY `idx_create_time` (`create_time`) COMMENT '创建时间索引',
    KEY `idx_pay_time` (`pay_time`) COMMENT '支付时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单表 - 存储所有支付订单信息';

-- =====================================================
-- 7. 待支付金额占位表 (fp_pending_pay_amount)
-- 说明：同一商户 + 同一支付方式下未过期未支付订单的 pay_amount 必须唯一，
-- 由唯一索引 uk_pending_pay_amount 兜底。下单时占位，订单结束（已支付 / 关闭 / 过期）时释放
-- =====================================================
DROP TABLE IF EXISTS `fp_pending_pay_amount`;
CREATE TABLE `fp_pending_pay_amount` (
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

-- =====================================================
-- 8. 未匹配收款通知表 (fp_unmatched_notify)
-- 说明：收到付款通知但按 (商户 + 支付方式 + pay_amount) 找不到对应待支付订单时落这张表，
-- 管理后台能查询并人工处理
-- =====================================================
DROP TABLE IF EXISTS `fp_unmatched_notify`;
CREATE TABLE `fp_unmatched_notify` (
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

-- =====================================================
-- 9. 登录失败次数记录表 (fp_login_attempt) —— MTM-162
-- 说明：登录限次的持久化状态，服务重启后失败次数不清零
-- =====================================================
DROP TABLE IF EXISTS `fp_login_attempt`;
CREATE TABLE `fp_login_attempt` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `scope` VARCHAR(20) NOT NULL COMMENT '作用域：admin-管理后台，merchant-商户后台',
    `identity_key` VARCHAR(200) NOT NULL COMMENT '钥匙标识：账号名归一化（trim+lowercase），或 "ip:" 前缀 + IP',
    `key_type` VARCHAR(10) NOT NULL COMMENT '钥匙类型：user-账号维度，ip-IP维度',
    `fail_count` INT NOT NULL DEFAULT 0 COMMENT '当前失败累计次数；成功登录后清零',
    `first_failed_at` DATETIME DEFAULT NULL COMMENT '本次统计窗口第一次失败时间',
    `last_failed_at` DATETIME DEFAULT NULL COMMENT '最近一次失败时间',
    `locked_until` DATETIME DEFAULT NULL COMMENT '锁定截止时间；为 NULL 或 <= now 视为未锁',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scope_identity` (`scope`, `identity_key`),
    KEY `idx_scope_locked_until` (`scope`, `locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录失败次数记录 - MTM-162 登录限次的持久化状态';

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
-- 7. fp_pending_pay_amount - 待支付金额占位表，保证同商户同支付方式下未支付订单的 pay_amount 唯一
-- 8. fp_unmatched_notify   - 未匹配收款通知表，钱到了但认不到订单的记录
-- 9. fp_login_attempt      - 登录失败次数记录表，MTM-162 登录限次的持久化状态
--
-- 关系说明：
-- - 一个商户(merchant)可以有多个店铺(shop)
-- - 一个商户(merchant)可以配置多个通道(channel)
-- - 一个店铺(shop)可以有多个二维码(qrcode)
-- - 一个订单(order)关联一个商户、一个店铺、一个二维码
-- =====================================================
