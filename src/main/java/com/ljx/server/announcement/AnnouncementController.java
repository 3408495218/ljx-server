package com.ljx.server.announcement;

import com.ljx.server.announcement.dto.AnnouncementDtos.PublicAnnouncementDto;
import com.ljx.server.announcement.dto.AnnouncementDtos.PublicAnnouncementListDto;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 玩家端公告：**公开免登录**（未登录用户在启动器里也要能看到公告）。
 * WebConfig 里已把它从玩家认证拦截范围排除。
 */
@RestController
@RequestMapping("/api/announcements")
@Tag(name = "公告")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @Operation(summary = "公告列表 + 轮播间隔（公开；无公告时 items 为空数组）")
    @GetMapping
    public ApiResponse<PublicAnnouncementListDto> list() {
        List<PublicAnnouncementDto> items = announcementService.listEnabled();
        // 间隔一起下发：客户端不再写死 8 秒，后台改了下次拉取即生效
        return ApiResponse.ok(new PublicAnnouncementListDto(items, announcementService.rotateSeconds()));
    }
}
