-- V16：VIP 档位可配置时长
--
-- 原先 VIP 时长写死在 CommerceService.VIP_DURATION_DAYS = 30，后台改不了。
-- 商品时长（置顶卡）早已在 shop_item.duration_days 里，这里让 VIP 档位也具备同样能力，
-- 两处口径一致：duration_days 为天数，NULL 表示永久。

ALTER TABLE vip_plan ADD COLUMN duration_days INT NULL;

-- 普通用户是默认档位、不可购买，时长无意义，保持 NULL
UPDATE vip_plan SET duration_days = 30 WHERE level > 0 AND duration_days IS NULL;
