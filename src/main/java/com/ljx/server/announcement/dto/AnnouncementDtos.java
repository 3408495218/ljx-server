package com.ljx.server.announcement.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AnnouncementDtos {

    /** 后台视图：含排序与开关 */
    public record AnnouncementDto(
            Long id,
            String content,
            int sortOrder,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 新增 / 修改请求；**修改时 null 表示该字段不变**（所以这里不能加 @NotBlank），
     * "内容不能为空"在 Service 里按新增/修改分别判断。
     */
    public record AnnouncementRequest(
            @Size(max = 500, message = "公告内容不能超过 500 字")
            String content,
            Integer sortOrder,
            Boolean enabled) {
    }

    /** 玩家端视图：只暴露内容，后台内部字段不外泄 */
    /**
     * 玩家端公告响应：列表 + **轮播间隔（秒）**。
     * <p>
     * 间隔由后台在「公告」面板配置（app_config.announcement_rotate_seconds），
     * 随公告一起下发，客户端就不必再写死 8 秒。
     */
    public record PublicAnnouncementListDto(List<PublicAnnouncementDto> items, int rotateSeconds) {
    }

    public record PublicAnnouncementDto(Long id, String content) {
    }
}
