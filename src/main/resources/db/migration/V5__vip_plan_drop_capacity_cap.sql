-- 垃圾侠平台 V5：容量与 VIP 档位解耦
-- 产品口径调整：房间容量由服主自行设置，不再与 VIP 等级挂钩。
-- 原先 vip_plan.capacity_cap 是容量上限的唯一来源（V4），现已成为死数据，一并删除。

ALTER TABLE vip_plan DROP COLUMN capacity_cap;

-- 描述里的「房间容量上限 N 人」已不成立，改为不承诺具体权益的占位
UPDATE vip_plan SET description = NULL WHERE level = 0;
UPDATE vip_plan SET description = 'VIP 专属权益敬请期待' WHERE level > 0;