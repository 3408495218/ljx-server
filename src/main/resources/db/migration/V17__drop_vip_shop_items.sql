-- V17：彻底删除 shop_item 里的 VIP 行
--
-- 背景：VIP 的唯一来源是 vip_plan（商城「VIP 档位」），但 V11 曾在 shop_item 里也建过 3 个
-- "VIP 商品"，V13 只是把它们下架。结果后台「道具商品」列表里仍能看到 3 个已下架的 VIP，
-- 与「VIP 档位」重复，干扰管理员判断。
--
-- 为什么 V13 没直接删、现在可以删：当时 shop_order.item_id 还有指向 shop_item 的外键，
-- 删除会让历史订单出现悬空引用；V14 已解绑该外键（订单里本就冗余了 item_name 与 price_coins，
-- 成交信息不依赖商品行）。所以现在物理删除是安全的，历史订单不受影响。

DELETE FROM shop_item WHERE effect_kind = 'VIP';
