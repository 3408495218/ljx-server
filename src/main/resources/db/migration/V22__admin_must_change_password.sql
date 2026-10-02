-- V22：管理员「必须修改密码」标记
--
-- 背景：管理员账号由 LJX_ADMIN_USERNAME / LJX_ADMIN_PASSWORD 在首次启动时创建，
-- 初始密码是运维自己设的、且往往就是文档里的默认值。只要有人拿默认密码登录成功，
-- 就能改商品、发 CDK、看数据库配置 —— 后台是平台最高权限入口，这个风险必须堵住。
--
-- 做法：首次创建或后来被重置的账号，标记 must_change_password = true，
-- 登录后除「修改密码」外的所有管理接口一律拒绝，直到本人改过密码为止。
--
-- 存量账号：全部标记为 true。因为它们都是用同一套环境变量创建的，
-- 无法区分谁已经改过；宁可让管理员多改一次，也不要留一个可能仍是默认密码的入口。

ALTER TABLE admin_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE admin_user SET must_change_password = TRUE;
