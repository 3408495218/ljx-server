package com.ljx.server.content.dto;

import com.ljx.server.content.entity.ContentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ContentDtos {

    public record RoomContentDto(
            String name,
            ContentType type,
            long sizeBytes,
            boolean enabled,
            LocalDateTime reportedAt) {
    }

    /** 房主上报的整份清单：以文件名为键，平台侧整表替换 */
    public record ReportRoomContentRequest(
            @NotNull(message = "内容不能为空") @Valid List<Item> items) {

        public record Item(
                @NotBlank(message = "文件名不能为空") @Size(max = 128, message = "文件名不能超过 128 字") String name,
                @NotNull(message = "缺少内容类型") ContentType type,
                @Min(value = 0, message = "体积不能为负") long sizeBytes,
                boolean enabled) {
        }
    }

    /** 客户端压缩包元数据；尚未上传时 data 为 null */
    public record ClientPackageDto(
            String fileName,
            long sizeBytes,
            LocalDateTime uploadedAt) {
    }
}