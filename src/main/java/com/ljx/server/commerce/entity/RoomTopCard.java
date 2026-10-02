package com.ljx.server.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 置顶卡效果：**房间维度**。房间在到期前会排在大厅前列，并在卡片左上角显示置顶角标。
 * <p>
 * 主键就是 room_id（一个房间同一时间只有一条置顶记录），续费时在原到期时间上顺延。
 */
@Entity
@Table(name = "room_top_card")
public class RoomTopCard {

    @Id
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "purchased_at", nullable = false)
    private LocalDateTime purchasedAt;

    protected RoomTopCard() {
    }

    public RoomTopCard(Long roomId, LocalDateTime expiresAt, LocalDateTime purchasedAt) {
        this.roomId = roomId;
        this.expiresAt = expiresAt;
        this.purchasedAt = purchasedAt;
    }

    /** 续费：未过期则在原到期时间上叠加，已过期则从现在开始算 */
    public void extendBy(LocalDateTime newExpiry, LocalDateTime now) {
        LocalDateTime base = expiresAt != null && expiresAt.isAfter(now) ? expiresAt : now;
        this.expiresAt = base.plus(java.time.Duration.between(now, newExpiry));
        this.purchasedAt = now;
    }

    /** 后台直接指定到期时间（覆盖而不是叠加） */
    public void overrideExpiry(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        this.purchasedAt = LocalDateTime.now();
    }

    public Long getRoomId() {
        return roomId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getPurchasedAt() {
        return purchasedAt;
    }
}
