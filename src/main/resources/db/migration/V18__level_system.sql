-- V18：普通玩家等级机制
--
-- 需求：玩家**每天登录一次获得一次经验**；升到下一级需要多少经验、升级后的客户端压缩包上传上限，
-- 都由后台决定。
--
-- 说明：account.level / account.score 从 V1 起就存在，但一直没有任何代码修改它们
-- （QuotaType 的按等级分档也是写死的 5/10/15/20/25/30），所以这里把整套机制建起来，
-- 并把"每级需要多少经验、允许多大上传"抽到配置表里，交给后台维护。

-- 等级配置：一级一行
CREATE TABLE level_config (
    level       INT          PRIMARY KEY,
    -- 从本级升到下一级需要的经验；<= 0 表示已封顶（不再升级）
    exp_to_next INT          NOT NULL,
    -- 该等级允许的客户端压缩包上传上限（MB）
    upload_mb   INT          NOT NULL,
    note        VARCHAR(64)  NULL
);

INSERT INTO level_config (level, exp_to_next, upload_mb, note) VALUES
    (0, 100,  5, '新注册'),
    (1, 200, 10, NULL),
    (2, 300, 15, NULL),
    (3, 500, 20, NULL),
    (4, 800, 25, NULL),
    (5,   0, 30, '当前封顶');

-- 通用键值配置：低频可配项放这里，避免为每项都建一张表
CREATE TABLE app_config (
    config_key   VARCHAR(64)  PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_config (config_key, config_value) VALUES ('daily_login_exp', '1');

-- 每日经验领取记录：用 DATE 比较，避免"今天是否已领"被时分秒干扰
ALTER TABLE account ADD COLUMN last_daily_reward_on DATE NULL;
