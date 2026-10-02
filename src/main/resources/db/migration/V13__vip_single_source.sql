-- V13：VIP 只保留 vip_plan 一份定义
--
-- 背景：V11 里我同时在 shop_item 建了 3 个"VIP 商品"、又在 vip_plan 里保留了 3 个档位——
-- 同一件事两处定义，结果道具商城里也冒出 VIP，图标还得靠前端硬编码，两边容易不一致。
-- 现在统一为：
--   * 道具商城 = 只放道具（目前仅置顶卡）
--   * VIP 档位 = 只在 vip_plan 定义，含名称、价格、客户端压缩包上限、房间边框、**图标**
--
-- 用"下架"而不是"删除"VIP 商品：shop_order 有指向 shop_item 的外键，
-- 删掉会破坏历史订单的完整性（订单里的 item_id 会变成悬空引用）。

UPDATE shop_item SET enabled = 0 WHERE effect_kind = 'VIP';

-- VIP 档位的图标（房间边框图另有 border_url，两者用途不同：一个是商城展示，一个是房间卡片外框）
ALTER TABLE vip_plan ADD COLUMN icon_url VARCHAR(160) NULL;

UPDATE vip_plan SET icon_url = NULL                      WHERE level = 0;  -- 普通用户不显示档位图标
UPDATE vip_plan SET icon_url = '/shop/vip-iron.png'       WHERE level = 1;  -- 铁块
UPDATE vip_plan SET icon_url = '/shop/vip-gold.png'       WHERE level = 2;  -- 金块
UPDATE vip_plan SET icon_url = '/shop/vip-diamond.png'    WHERE level = 3;  -- 钻石
