package com.ljx.server.content;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.content.dto.ContentDtos.ReportRoomContentRequest;
import com.ljx.server.content.dto.ContentDtos.RoomContentDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 房间内容镜像：插件 / Mod 的真实来源是房主本机目录，此处只做展示与上报 */
@RestController
@RequestMapping("/api/rooms/{roomId}/contents")
@Tag(name = "房间内容")
public class RoomContentController {

    private final RoomContentService roomContentService;

    public RoomContentController(RoomContentService roomContentService) {
        this.roomContentService = roomContentService;
    }

    @Operation(summary = "房间插件 / Mod 清单（登录可读；房主关闭可见性的清单对非房主不返回）")
    @GetMapping
    public ApiResponse<List<RoomContentDto>> list(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId) {
        return ApiResponse.ok(roomContentService.list(accountId, roomId));
    }

    @Operation(summary = "房主上报本机扫描结果（整表替换，仅房主）")
    @PutMapping
    public ApiResponse<List<RoomContentDto>> report(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId,
            @Valid @RequestBody ReportRoomContentRequest request) {
        return ApiResponse.ok(roomContentService.report(accountId, roomId, request));
    }
}