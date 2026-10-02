package com.ljx.server.room.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class RoomDtos {

    public record CreateRoomRequest(
            @NotBlank(message = "房间名不能为空") @Size(max = 64, message = "房间名不能超过 64 字") String name,
            @Size(max = 500, message = "简介不能超过 500 字") String intro,
            @NotBlank(message = "请选择服务端核心") @Size(max = 16) String core,
            @NotBlank(message = "请选择客户端版本") @Size(max = 16) String mcVersion,
            @Size(max = 16) String mode,
            @Min(value = 1, message = "容量至少为 1") int capacity,
            boolean locked,
            boolean noGuest,
            boolean needEmail,
            @Size(max = 255) String coverUrl) {
    }

    /** 局部更新：null 字段保持不变 */
    public record UpdateRoomRequest(
            @Size(max = 64, message = "房间名不能超过 64 字") String name,
            @Size(max = 500, message = "简介不能超过 500 字") String intro,
            @Size(max = 16) String core,
            @Size(max = 16) String mcVersion,
            @Size(max = 16) String mode,
            @Min(value = 1, message = "容量至少为 1") Integer capacity,
            Boolean locked,
            Boolean noGuest,
            Boolean needEmail,
            @Size(max = 255) String coverUrl,
            @Size(max = 64) String host,
            @Min(1) @Max(65535) Integer port,
            Boolean pluginListVisible,
            Boolean modListVisible) {
    }

    /** uptimeSec 当前仅透传不落库，留给后续运行时长统计 */
    public record HeartbeatRequest(
            @Min(value = 0, message = "人数不能为负") int players,
            long uptimeSec,
            /** 在线玩家名；房主端从本机服务端日志数出，平台侧只做转发 */
            @Size(max = 64, message = "在线名单过长") List<@Size(max = 16, message = "玩家名过长") String> playerNames) {
    }

    /**
     * 点亮QQ加群：code 为群号（展示用），idKey 为腾讯 WPA 组件的 idkey（加群链接凭据）。
     * 两者都传 null 表示取消点亮。idkey 限定字符集，避免把任意文本拼进加群链接。
     */
    public record QqGroupRequest(
            @Size(max = 32) String code,
            @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]{1,128}", message = "加群组件凭据格式不正确")
            String idKey) {
    }

    /**
     * 大厅卡片。
     * <p>
     * {@code vip} 是房主的**生效** VIP 档位（过期按 0 处理）；{@code border} 是该档位的
     * 房间边框**资源键**（如 border-iron，图片打包在桌面端）；{@code topCard} 表示房间当前置顶。
     */
    public record RoomSummaryDto(
            Long id,
            String name,
            String cover,
            int players,
            int capacity,
            int vip,
            String border,
            boolean topCard,
            boolean online,
            String core,
            String mcVersion,
            String mode) {
    }

    public record RoomDetailDto(
            Long id,
            String name,
            String intro,
            String core,
            String mcVersion,
            String mode,
            int capacity,
            int players,
            boolean locked,
            boolean noGuest,
            boolean needEmail,
            String qqGroupCode,
            /** 加群组件 idkey；非空即表示已点亮加群按钮 */
            String qqGroupIdKey,
            String cover,
            boolean online,
            String host,
            Integer port,
            long favoriteCount,
            String ownerName,
            int vip,
            boolean mine,
            boolean favorited,
            /** 插件 / Mod 清单是否对玩家可见；false 时玩家只看到总数，不列出文件 */
            boolean pluginListVisible,
            boolean modListVisible,
            /** 在线玩家名；随房主心跳整体覆盖（服务端里真实的玩家），用于判定成员是否「游戏中」 */
            List<String> playerNames,
            /** 房间成员（进入房间即算，含尚未进入游戏的人）；players 即成员数 */
            List<MemberDto> members) {
    }

    /**
     * 房间成员：进入房间即计数（房主与玩家一致）；inGame 表示该成员的名字
     * 是否出现在房主上报的服务端在线名单里（即"已进入游戏"）。
     */
    public record MemberDto(
            Long accountId,
            String username,
            int vip,
            int level,
            boolean inGame,
            boolean owner,
            LocalDateTime joinedAt) {
    }

    /** 进入 / 续期 / 离开房间的返回值：成员视图，前端玩家列表直接渲染 */
    public record PresenceResponse(
            List<MemberDto> members,
            int memberCount,
            int capacity) {
    }

    public record JoinResponse(String host, Integer port) {
    }
}