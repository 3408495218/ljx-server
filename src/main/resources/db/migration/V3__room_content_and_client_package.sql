-- V3：插件 / Mod 改为「以房主本机服务端目录为准」
--
-- 原先的平台内容库（plugin / plugin_version / room_plugin）没有消费方：桌面端不做内容分发，
-- 插件与 Mod 由房主自己放进服务端的 plugins / mods 目录，软件只负责管理本地文件，
-- 平台侧仅保留一份「上报镜像」供玩家查看。因此这里直接废弃整套内容库表。

DROP TABLE room_plugin;
DROP TABLE plugin_version;
DROP TABLE plugin;

-- 房间内容镜像：键是文件名，房主整表替换式上报；不参与配额计数
CREATE TABLE room_content (
    room_id     BIGINT       NOT NULL,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    enabled     TINYINT      NOT NULL DEFAULT 1,
    reported_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, name),
    CONSTRAINT fk_room_content_room FOREIGN KEY (room_id) REFERENCES room (id)
);

-- 客户端压缩包：房主上传到平台，玩家在「启动游戏」时下载后交给 PCL 导入，本软件不参与打包
CREATE TABLE client_package (
    room_id     BIGINT       NOT NULL PRIMARY KEY,
    file_name   VARCHAR(160) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    uploaded_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_client_package_room FOREIGN KEY (room_id) REFERENCES room (id)
);