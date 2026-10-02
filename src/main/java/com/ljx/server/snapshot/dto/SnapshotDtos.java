package com.ljx.server.snapshot.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class SnapshotDtos {

    public record CreateSnapshotRequest(
            @NotBlank(message = "快照名称不能为空") @Size(max = 64, message = "快照名称不能超过 64 字") String name,
            @Min(value = 0, message = "快照大小不能为负") long sizeBytes) {
    }

    public record SnapshotDto(Long id, String name, long sizeBytes, LocalDateTime createdAt) {
    }
}