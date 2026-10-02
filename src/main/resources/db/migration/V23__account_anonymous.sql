-- V23：账号「匿名（访客）」标记
--
-- 背景：本软件的定位是「开服器」，不强制登录也要能建房开服。
-- 未登录时客户端会自动申请一个"访客身份"（匿名账号），从而复用现有的房主鉴权
-- （改设置 / 删房 / 心跳 / 上传客户端包都靠 accountId 判定归属），玩家全程无感。
--
-- anonymous = TRUE 的账号：
--   * 由 POST /api/auth/guest 自动创建，密码是随机不可猜的值（因此无法被"登录"）；
--   * 名字形如「访客xxxxxx」；
--   * 仅用于承载"房主身份"，不代表一个完整的玩家账号（无邮箱、无每日奖励意义）。

ALTER TABLE account ADD COLUMN anonymous BOOLEAN NOT NULL DEFAULT FALSE;
