-- 垃圾侠平台 V7：插件 / Mod 清单对玩家的可见性
-- 产品口径：房主在「我的游戏」的插件 / Mod 页签切换开关，
-- 关闭后进房玩家只看到总数，不列出文件名；房主自己始终能看到完整列表。
-- 默认可见（1），保证既有房间行为不变。
-- 列名须与 Spring 的 CamelCaseToUnderscores 策略一致：pluginListVisible → plugin_list_visible。

ALTER TABLE room ADD COLUMN plugin_list_visible TINYINT NOT NULL DEFAULT 1;
ALTER TABLE room ADD COLUMN mod_list_visible TINYINT NOT NULL DEFAULT 1;