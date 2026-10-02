package com.ljx.server.lobby;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.api.PageResult;
import com.ljx.server.room.dto.RoomDtos.RoomSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@Tag(name = "大厅")
public class LobbyController {

    private final LobbyService lobbyService;

    public LobbyController(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @Operation(summary = "大厅房间列表：三视图（全部/收藏/足迹）+ 核心、版本、玩法、关键词筛选 + 分页；未登录也能看「全部」")
    @GetMapping
    public ApiResponse<PageResult<RoomSummaryDto>> list(
            // 未登录也能看大厅（本软件定位是开服器）；收藏/足迹视图没有身份时返回空列表
            @RequestAttribute(value = AuthInterceptor.ATTR_ACCOUNT_ID, required = false) Long accountId,
            @RequestParam(defaultValue = "all") String view,
            @RequestParam(required = false) String core,
            @RequestParam(required = false) String mcVersion,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "18") int size) {
        return ApiResponse.ok(lobbyService.list(accountId, view, core, mcVersion, mode, q, page, size));
    }
}