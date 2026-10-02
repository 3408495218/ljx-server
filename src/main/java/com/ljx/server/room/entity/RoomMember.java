package com.ljx.server.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 房间成员：谁「进入了房间」。由客户端 {@code PUT /api/rooms/{id}/presence} 登记并在房间页期间续期。
 * <p>
 * 与 {@link com.ljx.server.social.entity.JoinRecord}（足迹）职责分离：足迹永久保留，
 * 成员是易失数据，超时（90 秒）未续期即由 {@code MemberScheduler} 清理。
 */
@Entity
@Table(name = "room_member")
public class RoomMember {

    @EmbeddedId
    private RoomMemberId id;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /** 最近一次续期时间；客户端崩溃 / 断网 / 强杀时靠它兜底清理 */
    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;

    protected RoomMember() {
    }

    public RoomMember(Long roomId, Long accountId, LocalDateTime at) {
        this.id = new RoomMemberId(roomId, accountId);
        this.joinedAt = at;
        this.lastSeen = at;
    }

    public RoomMemberId getId() {
        return id;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    /** 续期：只刷新 last_seen，joined_at 不变（重复进入不视为重新加入） */
    public void renew(LocalDateTime at) {
        this.lastSeen = at;
    }
}
