package com.ljx.server.social.entity;

import java.io.Serializable;
import java.util.Objects;

/** room_favorite 的联合主键 */
public class RoomFavoriteId implements Serializable {

    private Long roomId;
    private Long accountId;

    public RoomFavoriteId() {
    }

    public RoomFavoriteId(Long roomId, Long accountId) {
        this.roomId = roomId;
        this.accountId = accountId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoomFavoriteId other)) {
            return false;
        }
        return Objects.equals(roomId, other.roomId) && Objects.equals(accountId, other.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, accountId);
    }
}