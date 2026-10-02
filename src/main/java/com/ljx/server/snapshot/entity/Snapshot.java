package com.ljx.server.snapshot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 快照元数据；快照文件本身保留在房主本机，云端只登记名称与大小 */
@Entity
@Table(name = "snapshot")
public class Snapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long roomId;

    private String name;

    private Long sizeBytes;

    private LocalDateTime createdAt;

    protected Snapshot() {
    }

    public Snapshot(Long roomId, String name, long sizeBytes) {
        this.roomId = roomId;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public String getName() {
        return name;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 同一名称重复登记时刷新大小（本地重建快照后体积会变） */
    public void updateSize(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}