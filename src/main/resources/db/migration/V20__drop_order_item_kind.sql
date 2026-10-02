-- V20：删掉 shop_order.item_kind
--
-- 原因：这张表同时有 item_kind（SHOP_ITEM / VIP）与 effect_kind（TOP_CARD / VIP），
-- 两个字段表达的是同一件事的两半，VIP 订单两者都是 VIP，道具订单是 SHOP_ITEM + TOP_CARD，
-- 容易让后来人分不清该读哪个。
-- 现在只留 effect_kind：
--   * effect_kind = 'TOP_CARD' → 道具订单，item_id 指向 shop_item；
--   * effect_kind = 'VIP'      → VIP 订单，item_id 为 NULL（VIP 来自 vip_plan，没有商品行）。
-- 也就是"订单来源"可由 item_id 是否为空推断，不必再存一列。

ALTER TABLE shop_order DROP COLUMN item_kind;
