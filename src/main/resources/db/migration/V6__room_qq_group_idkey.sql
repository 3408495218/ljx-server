-- 垃圾侠平台 V6：QQ 群加群凭据（P4）
-- 产品口径：房主「点亮QQ加群」走三步引导，最终存腾讯 WPA 组件的 idkey
-- （一键加群链接需要 idkey 拼参数，纯群号无法生成可用加群入口）。
-- 原有 qq_group_code VARCHAR(32) 语义调整为「群号（展示用）」，
-- 新增列承接加群组件凭据（实测 idkey 为 63 位，VARCHAR(128) 留一倍余量）。
-- 列名须与 Spring 的 CamelCaseToUnderscores 策略一致：qqGroupIdKey → qq_group_id_key。

ALTER TABLE room ADD COLUMN qq_group_id_key VARCHAR(128) NULL;