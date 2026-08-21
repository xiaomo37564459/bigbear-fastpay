-- =====================================================
-- Fast 易支付 数据库迁移脚本 V1.5（PostgreSQL）—— URL 列加宽到 2000 字符
-- 说明：老库升级用。新库直接执行 init-pg.sql 就是新结构，不需要跑这里。
--
-- 变更点：
--   1. fp_pay_order.notify_url  从 VARCHAR(255) 放宽到 VARCHAR(2000)
--   2. fp_pay_order.return_url  从 VARCHAR(255) 放宽到 VARCHAR(2000)
--   3. fp_merchant.notify_url   从 VARCHAR(255) 放宽到 VARCHAR(2000)
--   4. fp_merchant.return_url   从 VARCHAR(255) 放宽到 VARCHAR(2000)
--
-- 为什么现在补这个脚本：
--   - MTM-151 上线联调时发现：彩虹易支付协议（sub2api）真实回调的 return_url 会带
--     resume_token（JWT），单条长度 ~344 字符，直接把 VARCHAR(255) 撑爆，报
--     "value too long for type character varying(255)"，接口 503，用户支付失败。
--   - 生产库当时是**手工**在服务器上跑 /root/fastpay-ops/widen-url-cols.sql 把这
--     4 列加宽到 2000 临时解决的，init-pg.sql / init.sql 也同步更新了（commit
--     0f00b86），但**没有沉淀成正式的迁移脚本**。
--   - 结果：生产已经是 2000，全新装机（跑 init-pg.sql）也是 2000，但任何**从老
--     init 建起来、没跟 0f00b86 之后重建**的环境（历史 dev/test 库、灾备快照）
--     仍然是 VARCHAR(255)，一旦切进 sub2api 场景就会再次踩雷。
--   - 本脚本把手工那一步固化下来，让"跑迁移"和"跑手工 SQL"合成同一件事，避免
--     再出现"仓库里没写清怎么升级"这种黑洞。（MTM-209）
--
-- 兼容说明（不需要停机、不影响任何在跑的服务）：
--   - PostgreSQL 9.2+ 里 VARCHAR(n) → VARCHAR(m)（m > n）只改元数据、不重写表、
--     不锁行，即使表里已有 20 万行订单也是毫秒级完成。
--   - Java 实体（PayOrder / Merchant）里 notifyUrl / returnUrl 是不带 length
--     注解的 String，不需要改代码、不需要重编译、不需要重启后端。
--   - 老数据一行不动。已有 URL 短的照常，新写入允许更长的 URL。
--
-- 幂等说明：
--   - PG 的 ALTER COLUMN ... TYPE 没有内置 IF 条件，重复对已经是 2000 的列执行
--     "改成 2000" 是空操作，不会报错、不会阻塞、也不重写表。所以本脚本可以在
--     "已经通过 widen-url-cols.sql 加宽过"的生产库上再跑一遍，安全无副作用。
--
-- 生产库怎么跑（在部署新版 jar、重启后端之前执行）：
--   psql -h <host> -p 5432 -U <user> -d bigbear_fastpay -f V1_5__widen_url_columns_pg.sql
--
-- 顺序说明：
--   - 上线路径不敏感，本脚本"先跑再上 jar"或"上 jar 之后再跑"都不会出问题
--     （新旧 jar 都能写 ≤255 的老短 URL；只有新场景才会写 >255 的长 URL）。
--     但为了和其他迁移脚本步调一致，仍然建议先跑脚本再重启后端。
--
-- 怎么回退：见文件末尾的「回退脚本」注释块。**动手之前先读那段说明。**
-- =====================================================

BEGIN;

-- 加宽订单表的两个 URL 列
ALTER TABLE fp_pay_order ALTER COLUMN notify_url TYPE VARCHAR(2000);
ALTER TABLE fp_pay_order ALTER COLUMN return_url TYPE VARCHAR(2000);

-- 加宽商户默认 URL 列（下单时如果没传 notify_url/return_url，会继承商户默认值）
ALTER TABLE fp_merchant ALTER COLUMN notify_url TYPE VARCHAR(2000);
ALTER TABLE fp_merchant ALTER COLUMN return_url TYPE VARCHAR(2000);

COMMIT;

-- =====================================================
-- 回退脚本 —— 动手之前先读这一段
-- =====================================================
-- 【默认回退方式：只把 jar 退回上一个 tag，不要缩这四列。】
--   老版本 jar 对着 VARCHAR(2000) 的库能正常跑（列宽只是上限，不影响短 URL 写入），
--   所以退 jar 就够了，四列留在 2000 不影响任何功能，也不占额外空间。
--
-- 【不要在库里已经有 >255 长 URL 的时候缩回 255。】
--   PG 会去逐行检查现有数据是否超长，任何一行超过 255 都会直接报错并回滚整个
--   ALTER；即使成功缩回，历史上那笔长 URL 的订单查询也会失效。
--
-- 【只有确定这个版本以后不再上、且库里从没写过 >255 的 URL，才在业务低峰缩回。】
--   顺序：先把 jar 退回上一个 tag 并确认服务正常 → 用下面的 SELECT 确认没有超长数据
--   → 再执行下面四行 ALTER。
-- =====================================================
-- -- 确认没有超长数据（四条都返回 0 才可以缩）：
-- SELECT COUNT(*) FROM fp_pay_order WHERE LENGTH(notify_url) > 255;
-- SELECT COUNT(*) FROM fp_pay_order WHERE LENGTH(return_url) > 255;
-- SELECT COUNT(*) FROM fp_merchant  WHERE LENGTH(notify_url) > 255;
-- SELECT COUNT(*) FROM fp_merchant  WHERE LENGTH(return_url) > 255;
--
-- -- 全部为 0 之后再执行：
-- BEGIN;
-- ALTER TABLE fp_pay_order ALTER COLUMN notify_url TYPE VARCHAR(255);
-- ALTER TABLE fp_pay_order ALTER COLUMN return_url TYPE VARCHAR(255);
-- ALTER TABLE fp_merchant  ALTER COLUMN notify_url TYPE VARCHAR(255);
-- ALTER TABLE fp_merchant  ALTER COLUMN return_url TYPE VARCHAR(255);
-- COMMIT;
