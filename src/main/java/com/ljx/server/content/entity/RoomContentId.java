package com.ljx.server.content.entity;

import java.io.Serializable;
import java.util.Objects;

/** 房间内容以文件名为键：同一房间内不允许重名文件 */
public class RoomContentId implements Serializable {

    private Long roomId;
    private String name;

    public RoomContentId() {
    }

    public RoomContentId(Long roomId, String name) {
        this.roomId = roomId;
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoomContentId other)) {
            return false;
        }
        return Objects.equals(roomId, other.roomId) && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, name);
    }
}