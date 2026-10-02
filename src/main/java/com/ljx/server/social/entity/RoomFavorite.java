package com.ljx.server.social.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 收藏；收藏/取消接口在 B3 交付，B1 只做大厅「收藏」视图与详情页收藏数读取 */
@Entity
@Table(name = "room_favorite")
@IdClass(RoomFavoriteId.class)
public class RoomFavorite {

    @Id
    private Long roomId;

    @Id
    private Long accountId;

    private LocalDateTime createdAt;

    protected RoomFavorite() {
    }

    public RoomFavorite(Long roomId, Long accountId) {
        this.roomId = roomId;
        this.accountId = accountId;
        this.createdAt = LocalDateTime.now();
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}