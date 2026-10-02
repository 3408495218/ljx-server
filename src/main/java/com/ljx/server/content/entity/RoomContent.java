package com.ljx.server.content.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 房间内容的「上报镜像」：真实来源是房主本机服务端目录里的 jar 文件，
 * 房主每次扫描后整表替换上报，平台只用于向玩家展示，不做分发。
 */
@Entity
@Table(name = "room_content")
@IdClass(RoomContentId.class)
public class RoomContent {

    @Id
    private Long roomId;

    @Id
    private String name;

    @Enumerated(EnumType.STRING)
    private ContentType type;

    private long sizeBytes;

    private boolean enabled;

    private LocalDateTime reportedAt;

    protected RoomContent() {
    }

    public RoomContent(Long roomId, String name, ContentType type, long sizeBytes,
                       boolean enabled, LocalDateTime reportedAt) {
        this.roomId = roomId;
        this.name = name;
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.enabled = enabled;
        this.reportedAt = reportedAt;
    }

    public Long getRoomId() {
        return roomId;
    }

    public String getName() {
        return name;
    }

    public ContentType getType() {
        return type;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }
}