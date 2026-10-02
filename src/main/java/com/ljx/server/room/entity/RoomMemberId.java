package com.ljx.server.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** room_member 复合主键：同一账号在同一房间只算一个成员（多设备/多窗口不重复计数） */
@Embeddable
public class RoomMemberId implements Serializable {

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    protected RoomMemberId() {
    }

    public RoomMemberId(Long roomId, Long accountId) {
        this.roomId = roomId;
        this.accountId = accountId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getAccountId() {
        return accountId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoomMemberId other)) {
            return false;
        }
        return Objects.equals(roomId, other.roomId) && Objects.equals(accountId, other.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, accountId);
    }
}
