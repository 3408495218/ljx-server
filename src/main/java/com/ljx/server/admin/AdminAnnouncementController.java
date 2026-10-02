package com.ljx.server.admin;

import com.ljx.server.announcement.AnnouncementService;
import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementDto;
import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementRequest;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/announcements")
@Tag(name = "管理后台-公告")
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;
    private final com.ljx.server.common.audit.AuditService auditService;

    public AdminAnnouncementController(AnnouncementService announcementService,
                                       com.ljx.server.common.audit.AuditService auditService) {
        this.announcementService = announcementService;
        this.auditService = auditService;
    }

    @Operation(summary = "公告列表（含已关闭）")
    @GetMapping
    public ApiResponse<List<AnnouncementDto>> list() {
        return ApiResponse.ok(announcementService.listAll());
    }

    public record RotateSecondsRequest(
            @jakarta.validation.constraints.Min(value = 3, message = "轮播间隔最少 3 秒")
            @jakarta.validation.constraints.Max(value = 120, message = "轮播间隔最多 120 秒")
            int seconds) {
    }

    /** 读当前轮播间隔（玩家端底部状态栏每条公告停留多少秒） */
    @Operation(summary = "读取公告轮播间隔（秒）")
    @GetMapping("/rotate-seconds")
    public ApiResponse<Integer> rotateSeconds() {
        return ApiResponse.ok(announcementService.rotateSeconds());
    }

    @Operation(summary = "修改公告轮播间隔（秒，3-120）")
    @PutMapping("/rotate-seconds")
    public ApiResponse<Integer> updateRotateSeconds(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody RotateSecondsRequest request) {
        int saved = announcementService.updateRotateSeconds(request.seconds());
        auditService.record(null, com.ljx.server.common.audit.AuditAction.ANNOUNCEMENT_UPDATE,
                "管理员#" + adminId + " 将公告轮播间隔改为 " + saved + " 秒", true);
        return ApiResponse.ok(saved);
    }

    @Operation(summary = "新增公告")
    @PostMapping
    public ApiResponse<AnnouncementDto> create(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.ok(announcementService.create(adminId, request));
    }

    @Operation(summary = "修改公告（null 字段保持不变）")
    @PutMapping("/{id}")
    public ApiResponse<AnnouncementDto> update(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.ok(announcementService.update(adminId, id, request));
    }

    @Operation(summary = "删除公告")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable Long id) {
        announcementService.delete(adminId, id);
        return ApiResponse.ok();
    }
}
