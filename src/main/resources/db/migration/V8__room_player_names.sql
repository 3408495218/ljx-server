-- 垃圾侠平台 V8：房间在线玩家名单
-- 产品口径：房主端从本机服务端日志数出在线玩家，「当前加入」页据此显示真实名单。
-- 平台看不到房主本机的服务端，心跳是唯一通道，因此名单随心跳整体覆盖。
-- 逗号分隔存储：玩家名只含 [A-Za-z0-9_]，与分隔符不冲突；名单是易失数据，无需单独建表。
-- 列名须与 Spring 的 CamelCaseToUnderscores 策略一致：playerNames → player_names。

ALTER TABLE room ADD COLUMN player_names VARCHAR(1024);