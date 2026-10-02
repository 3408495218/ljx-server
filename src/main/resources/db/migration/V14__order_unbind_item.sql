-- V14：订单不再强绑商品表
--
-- 原因：VIP 的唯一来源是 vip_plan，买 VIP 不会产生 shop_item 行，
-- 而 shop_order.item_id 原本 NOT NULL + 外键指向 shop_item，VIP 订单根本插不进去。
-- 订单里已经冗余了 item_name 与 price_coins（成交当时的真实信息），
-- 所以外键本来也是多余的：商品改名/调价/下架都不该影响历史订单。

ALTER TABLE shop_order DROP CONSTRAINT fk_shop_order_item;
ALTER TABLE shop_order ALTER COLUMN item_id DROP NOT NULL;
ALTER TABLE shop_order ADD COLUMN item_kind VARCHAR(24) NOT NULL DEFAULT 'SHOP_ITEM';
