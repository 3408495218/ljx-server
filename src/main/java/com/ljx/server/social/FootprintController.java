package com.ljx.server.social;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 足迹列表读取复用大厅三视图（/api/rooms?view=history），此处只提供清理入口 */
@RestController
@RequestMapping("/api/me/footprint")
@Tag(name = "足迹")
public class FootprintController {

    private final FootprintService footprintService;

    public FootprintController(FootprintService footprintService) {
        this.footprintService = footprintService;
    }

    @Operation(summary = "移除某房间的足迹（幂等）")
    @DeleteMapping("/{roomId}")
    public ApiResponse<Void> remove(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId) {
        footprintService.remove(accountId, roomId);
        return ApiResponse.ok();
    }

    @Operation(summary = "清空足迹（幂等）")
    @DeleteMapping
    public ApiResponse<Void> clear(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId) {
        footprintService.clear(accountId);
        return ApiResponse.ok();
    }
}