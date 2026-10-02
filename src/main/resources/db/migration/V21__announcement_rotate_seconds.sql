-- V21：公告轮播间隔改为后台可配
--
-- 原先 8 秒是写死在客户端的，运营想放慢（让玩家读完）或加快都改不了。
-- 放到 app_config 里，后台「公告」面板可直接改，客户端每次拉公告时一并取走。

INSERT INTO app_config (config_key, config_value)
SELECT 'announcement_rotate_seconds', '8'
 WHERE NOT EXISTS (SELECT 1 FROM app_config WHERE config_key = 'announcement_rotate_seconds');
