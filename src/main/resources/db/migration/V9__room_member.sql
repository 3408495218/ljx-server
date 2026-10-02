-- V9：房间成员（谁「进入了房间」，用于房间人数与玩家列表）
--
-- 与 join_record（足迹）职责分离：足迹是永久历史（大厅「足迹」视图），
-- 成员是易失数据——客户端崩溃/断网后靠 90 秒 last_seen 超时淘汰，不能混用。
-- 房间人数 room.players 由本表行数物化写入，服务端在线名单仍由 room.player_names 承载。

CREATE TABLE room_member (
    room_id    BIGINT    NOT NULL,
    account_id BIGINT    NOT NULL,
    joined_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen  TIMESTAMP NOT NULL,
    PRIMARY KEY (room_id, account_id),
    CONSTRAINT fk_room_member_room    FOREIGN KEY (room_id)    REFERENCES room (id),
    CONSTRAINT fk_room_member_account FOREIGN KEY (account_id) REFERENCES account (id)
);

-- 超时清理按 last_seen 扫描
CREATE INDEX idx_room_member_last_seen ON room_member (last_seen);
