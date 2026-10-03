-- V14：订单不再强绑商品表
--
-- 原因：VIP 的唯一来源是 vip_plan，买 VIP 不会产生 shop_item 行，
-- 而 shop_order.item_id 原本 NOT NULL + 外键指向 shop_item，VIP 订单根本插不进去。
-- 订单里已经冗余了 item_name 与 price_coins（成交当时的真实信息），
-- 所以外键本来也是多余的：商品改名/调价/下架都不该影响历史订单。

-- ⚠️ 语法说明（**非常重要，别改回 PostgreSQL 写法**）：
--   本文件必须同时能在 **H2（MODE=MySQL，开发与测试）** 与 **MySQL 8（生产）** 上执行。
--   原先写的是 **PostgreSQL 方言**，在 MySQL 上必然中断：
--     · `DROP CONSTRAINT` —— MySQL 8.0.19+ 恰好可用（等价 DROP FOREIGN KEY），但语义不明确
--     · `ALTER COLUMN ... DROP NOT NULL` —— MySQL **根本没有这种写法**，报 1064 语法错误
--   统一改成 MySQL 方言。H2 的 MySQL 兼容模式实测同样接受下面两句（已单独验证）。
ALTER TABLE shop_order DROP FOREIGN KEY fk_shop_order_item;
ALTER TABLE shop_order MODIFY COLUMN item_id BIGINT NULL;
ALTER TABLE shop_order ADD COLUMN item_kind VARCHAR(24) NOT NULL DEFAULT 'SHOP_ITEM';
