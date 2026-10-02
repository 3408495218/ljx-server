package com.ljx.server.room;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.room.dto.RoomDtos.CreateRoomRequest;
import com.ljx.server.room.dto.RoomDtos.HeartbeatRequest;
import com.ljx.server.room.dto.RoomDtos.JoinResponse;
import com.ljx.server.room.dto.RoomDtos.PresenceResponse;
import com.ljx.server.room.dto.RoomDtos.QqGroupRequest;
import com.ljx.server.room.dto.RoomDtos.RoomDetailDto;
import com.ljx.server.room.dto.RoomDtos.RoomSummaryDto;
import com.ljx.server.room.dto.RoomDtos.UpdateRoomRequest;
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
@RequestMapping("/api/rooms")
@Tag(name = "房间")
public class RoomController {

    private final RoomService roomService;
    private final MemberService memberService;

    public RoomController(RoomService roomService, MemberService memberService) {
        this.roomService = roomService;
        this.memberService = memberService;
    }

    @Operation(summary = "创建房间（需绑定邮箱；容量由房主自设，服务端不设上限）")
    @PostMapping
    public ApiResponse<RoomDetailDto> create(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @Valid @RequestBody CreateRoomRequest request) {
        return ApiResponse.ok(roomService.create(accountId, request));
    }

    @Operation(summary = "我的游戏：当前账号名下的房间列表（新建优先）")
    @GetMapping("/mine")
    public ApiResponse<List<RoomSummaryDto>> mine(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId) {
        return ApiResponse.ok(roomService.myRooms(accountId));
    }

    @Operation(summary = "房间详情：介绍、核心、版本、收藏数（插件 / Mod 计数见 /contents 清单）")
    @GetMapping("/{id}")
    public ApiResponse<RoomDetailDto> detail(
            // 未登录也能看房间详情；此时 mine=false、favorited=false
            @RequestAttribute(value = AuthInterceptor.ATTR_ACCOUNT_ID, required = false) Long accountId,
            @PathVariable Long id) {
        return ApiResponse.ok(roomService.detail(accountId, id));
    }

    @Operation(summary = "修改房间（仅房主；null 字段保持不变，可一并设置公网地址）")
    @PutMapping("/{id}")
    public ApiResponse<RoomDetailDto> update(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoomRequest request) {
        return ApiResponse.ok(roomService.update(accountId, id, request));
    }

    @Operation(summary = "删除房间（软删除，仅房主）")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id) {
        roomService.delete(accountId, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "心跳上报（仅房主，客户端每 30s 一次；含在线玩家名单）")
    @PostMapping("/{id}/heartbeat")
    public ApiResponse<Void> heartbeat(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id,
            @Valid @RequestBody HeartbeatRequest request) {
        roomService.heartbeat(accountId, id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "点亮 / 取消点亮QQ加群（仅房主；传群号与加群组件凭据，均为空即取消）")
    @PostMapping("/{id}/qq-group")
    public ApiResponse<RoomDetailDto> setQqGroup(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id,
            @Valid @RequestBody QqGroupRequest request) {
        return ApiResponse.ok(roomService.setQqGroup(accountId, id, request.code(), request.idKey()));
    }

    @Operation(summary = "加入房间（校验上锁、在线、邮箱门槛）→ 房主公网地址")
    @PostMapping("/{id}/join")
    public ApiResponse<JoinResponse> join(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id) {
        return ApiResponse.ok(roomService.join(accountId, id));
    }

    @Operation(summary = "进入房间 / 续期（幂等）：登记为房间成员，返回成员视图（玩家列表）")
    @PutMapping("/{id}/presence")
    public ApiResponse<PresenceResponse> enterRoom(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id) {
        return ApiResponse.ok(memberService.enter(accountId, id));
    }

    @Operation(summary = "离开房间（幂等）：移除成员，房间离线时同样可用")
    @DeleteMapping("/{id}/presence")
    public ApiResponse<Void> leaveRoom(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long id) {
        memberService.leave(accountId, id);
        return ApiResponse.ok();
    }
}