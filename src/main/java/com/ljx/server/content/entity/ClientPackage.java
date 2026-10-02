package com.ljx.server.content.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 客户端压缩包：一房一包，房主上传到平台，玩家下载后交给 PCL 导入 */
@Entity
@Table(name = "client_package")
public class ClientPackage {

    @Id
    private Long roomId;

    private String fileName;

    private long sizeBytes;

    private LocalDateTime uploadedAt;

    protected ClientPackage() {
    }

    public ClientPackage(Long roomId, String fileName, long sizeBytes, LocalDateTime uploadedAt) {
        this.roomId = roomId;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = uploadedAt;
    }

    /** 重新上传即替换，房间只保留最新一份包 */
    public void replace(String fileName, long sizeBytes, LocalDateTime uploadedAt) {
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = uploadedAt;
    }

    public Long getRoomId() {
        return roomId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
}