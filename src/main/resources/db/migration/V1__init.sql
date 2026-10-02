-- 垃圾侠平台 V1：全量核心表（方案 §4）
-- 兼容 MySQL 8 与 H2（MODE=MySQL）

-- 账号：等级与分数由 score_event 流水计算，此处为物化结果
CREATE TABLE account (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    level         INT          NOT NULL DEFAULT 0,
    score         INT          NOT NULL DEFAULT 0,
    vip_level     INT          NOT NULL DEFAULT 0,
    coins         INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at    TIMESTAMP    NULL,
    CONSTRAINT uk_account_username UNIQUE (username)
);

-- refresh token 一次性轮换：重放已撤销的 token 将撤销该账号全部会话
CREATE TABLE auth_refresh_token (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT       NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    revoked_at TIMESTAMP    NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_refresh_token_account ON auth_refresh_token (account_id);

-- 邮箱绑定：验证码 BCrypt 存储、5 分钟有效（替代短信，控制成本）
CREATE TABLE email_binding (
    account_id      BIGINT PRIMARY KEY,
    email           VARCHAR(255) NULL,
    code_hash       VARCHAR(100) NULL,
    code_expires_at TIMESTAMP    NULL,
    code_sent_at    TIMESTAMP    NULL,
    verified        TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_email_binding_account FOREIGN KEY (account_id) REFERENCES account (id)
);

-- 房间：服务端进程在房主本机，后端只做登记与发现；软删除
CREATE TABLE room (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id          BIGINT       NOT NULL,
    name              VARCHAR(64)  NOT NULL,
    intro             VARCHAR(500) NULL,
    core              VARCHAR(16)  NOT NULL,
    mc_version        VARCHAR(16)  NOT NULL,
    mode              VARCHAR(16)  NOT NULL DEFAULT '生存',
    capacity          INT          NOT NULL,
    players           INT          NOT NULL DEFAULT 0,
    locked            TINYINT      NOT NULL DEFAULT 0,
    no_guest          TINYINT      NOT NULL DEFAULT 0,
    need_email       TINYINT      NOT NULL DEFAULT 0,
    qq_group_code     VARCHAR(32)  NULL,
    cover_url         VARCHAR(255) NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE',
    host              VARCHAR(64)  NULL,
    port              INT          NULL,
    last_heartbeat_at TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at        TIMESTAMP    NULL,
    CONSTRAINT fk_room_owner FOREIGN KEY (owner_id) REFERENCES account (id)
);
CREATE INDEX idx_room_status ON room (status, updated_at);
CREATE INDEX idx_room_owner ON room (owner_id);

-- 收藏
CREATE TABLE room_favorite (
    room_id    BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, account_id),
    CONSTRAINT fk_favorite_room FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT fk_favorite_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_favorite_account ON room_favorite (account_id);

-- 足迹（加入记录），同时产出互动分数事件
CREATE TABLE join_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id    BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    joined_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_join_room FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT fk_join_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_join_account ON join_record (account_id, joined_at);
CREATE INDEX idx_join_room ON join_record (room_id);

-- 配额：生效值 = base_value(等级) + add_value(购买)，封顶 limit_value
CREATE TABLE quota (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id  BIGINT      NOT NULL,
    type        VARCHAR(32) NOT NULL,
    base_value  INT         NOT NULL,
    add_value   INT         NOT NULL DEFAULT 0,
    limit_value INT         NOT NULL DEFAULT 2147483647,
    CONSTRAINT uk_quota_account_type UNIQUE (account_id, type),
    CONSTRAINT fk_quota_account FOREIGN KEY (account_id) REFERENCES account (id)
);

-- 内容库：插件与 Mod 复用同一套表，type 区分
CREATE TABLE plugin (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    type       VARCHAR(16) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    core       VARCHAR(16) NULL,
    category   VARCHAR(32) NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_plugin_type_name UNIQUE (type, name)
);

CREATE TABLE plugin_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plugin_id     BIGINT       NOT NULL,
    mc_version    VARCHAR(16)  NOT NULL,
    version       VARCHAR(32)  NOT NULL,
    download_url  VARCHAR(255) NOT NULL,
    size_bytes    BIGINT       NULL,
    stable        TINYINT      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plugin_version_plugin FOREIGN KEY (plugin_id) REFERENCES plugin (id)
);
CREATE INDEX idx_plugin_version_plugin ON plugin_version (plugin_id);

-- 房间已安装内容，配额按此计数
CREATE TABLE room_plugin (
    room_id           BIGINT NOT NULL,
    plugin_version_id BIGINT NOT NULL,
    installed_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, plugin_version_id),
    CONSTRAINT fk_room_plugin_room FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT fk_room_plugin_version FOREIGN KEY (plugin_version_id) REFERENCES plugin_version (id)
);

-- 快照：仅元数据，文件保留在房主本地
CREATE TABLE snapshot (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id    BIGINT      NOT NULL,
    name       VARCHAR(64) NOT NULL,
    size_bytes BIGINT      NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_snapshot_room FOREIGN KEY (room_id) REFERENCES room (id)
);
CREATE INDEX idx_snapshot_room ON snapshot (room_id);

-- 分数流水：等级可由该表重算
CREATE TABLE score_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT      NOT NULL,
    type       VARCHAR(32) NOT NULL,
    delta      INT         NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_score_event_account FOREIGN KEY (account_id) REFERENCES account (id)
);
CREATE INDEX idx_score_event_account ON score_event (account_id);
