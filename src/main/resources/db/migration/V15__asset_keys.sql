-- V15：图标与边框改为「资源键」，不再存后端 URL
--
-- 原因：桌面端渲染 <img src="/shop/vip-iron.png"> 时，相对路径会被解析成
-- tauri://localhost/shop/vip-iron.png —— 根本不是后端地址，图片全裂。
-- 改为：图片**打包在桌面端**（src/assets/shop/），后端只给一个键（如 vip-iron），
-- 由前端映射表取图。这样客户端离线、后端换地址都不影响图标显示。

UPDATE shop_item SET icon_url = 'top-card' WHERE effect_kind = 'TOP_CARD';

UPDATE vip_plan SET icon_url = 'vip-iron'    WHERE level = 1;
UPDATE vip_plan SET icon_url = 'vip-gold'    WHERE level = 2;
UPDATE vip_plan SET icon_url = 'vip-diamond' WHERE level = 3;

UPDATE vip_plan SET border_url = 'border-iron'    WHERE level = 1;
UPDATE vip_plan SET border_url = 'border-gold'    WHERE level = 2;
UPDATE vip_plan SET border_url = 'border-diamond' WHERE level = 3;
