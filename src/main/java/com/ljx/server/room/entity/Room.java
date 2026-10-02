package com.ljx.server.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/** 房间登记信息；服务端进程在房主本机运行，后端只存登记与在线态 */
@Entity
@Table(name = "room")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ownerId;

    private String name;

    private String intro;

    private String core;

    private String mcVersion;

    private String mode;

    private int capacity;

    private int players;

    /** 在线玩家名单，逗号分隔；平台看不到房主本机的服务端，只能随心跳整体覆盖 */
    private String playerNames;

    private boolean locked;

    private boolean noGuest;

    private boolean needEmail;

    private String qqGroupCode;

    /** 腾讯 WPA 加群组件的 idkey；有值时房间展示可用的「加入QQ群」入口 */
    private String qqGroupIdKey;

    private String coverUrl;

    /** 插件 / Mod 清单对进房玩家是否可见；房主自己始终可见，开关只影响玩家侧展示 */
    private boolean pluginListVisible = true;

    private boolean modListVisible = true;

    @Enumerated(EnumType.STRING)
    private RoomStatus status;

    private String host;

    private Integer port;

    private LocalDateTime lastHeartbeatAt;

    /**
     * 置顶到期时间（room_top_card 的冗余副本，仅供大厅排序用）。
     * 空 = 未置顶或已过期；过期的由 TopCardScheduler 定时置回 null，
     * 否则 DESC 排序下"已过期的置顶房"会排在没有置顶的房间之前。
     */
    @Column(name = "top_expires_at")
    private LocalDateTime topExpiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    protected Room() {
    }

    public Room(Long ownerId, String name, String intro, String core, String mcVersion, String mode, int capacity,
                boolean locked, boolean noGuest, boolean needEmail, String coverUrl) {
        this.ownerId = ownerId;
        this.name = name;
        this.intro = intro;
        this.core = core;
        this.mcVersion = mcVersion;
        this.mode = mode;
        this.capacity = capacity;
        this.locked = locked;
        this.noGuest = noGuest;
        this.needEmail = needEmail;
        this.coverUrl = coverUrl;
        this.players = 0;
        this.status = RoomStatus.OFFLINE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** null 表示该字段不变 */
    public void update(String name, String intro, String core, String mcVersion, String mode, Integer capacity,
                       Boolean locked, Boolean noGuest, Boolean needEmail, String coverUrl) {
        if (name != null) this.name = name;
        if (intro != null) this.intro = intro;
        if (core != null) this.core = core;
        if (mcVersion != null) this.mcVersion = mcVersion;
        if (mode != null) this.mode = mode;
        if (capacity != null) this.capacity = capacity;
        if (locked != null) this.locked = locked;
        if (noGuest != null) this.noGuest = noGuest;
        if (needEmail != null) this.needEmail = needEmail;
        if (coverUrl != null) this.coverUrl = coverUrl;
    }

    /**
     * 心跳：刷新在线态、在线名单与心跳时间。
     * <p>
     * <b>不再写 players</b>——players 的语义已改为「房间成员数」（进入房间即计数），
     * 由 {@link com.ljx.server.room.MemberService} 维护；服务端里真实的在线玩家由
     * playerNames 承载，成员是否「游戏中」靠它判定。
     */
    public void heartbeat(List<String> names, LocalDateTime at) {
        applyPlayerNames(names);
        this.status = RoomStatus.ONLINE;
        this.lastHeartbeatAt = at;
    }

    /** 房间成员数（物化）；由 MemberService 在成员进出 / 超时清理后写入 */
    public void setMemberCount(int count) {
        this.players = Math.max(0, count);
    }

    /** 在线玩家名单；未上报或已清空时返回空列表 */
    public List<String> listPlayerNames() {
        if (playerNames == null || playerNames.isEmpty()) {
            return List.of();
        }
        return List.of(playerNames.split(","));
    }

    /** 名单随心跳整体覆盖；空名单落成 null，避免留下一个空串占位 */
    private void applyPlayerNames(List<String> names) {
        this.playerNames = (names == null || names.isEmpty()) ? null : String.join(",", names);
    }

    /** 房主公网地址，加入房间时下发给玩家 */
    public void setPublicAddress(String host, Integer port) {
        if (host != null) this.host = host;
        if (port != null) this.port = port;
    }

    /** 群号与 idkey 一并覆盖；传 null 即取消点亮 */
    public void setQqGroup(String code, String idKey) {
        this.qqGroupCode = code;
        this.qqGroupIdKey = idKey;
    }

    /** 清单对玩家的可见性；null 表示该字段不变 */
    public void setListVisibility(Boolean pluginVisible, Boolean modVisible) {
        if (pluginVisible != null) this.pluginListVisible = pluginVisible;
        if (modVisible != null) this.modListVisible = modVisible;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.status = RoomStatus.OFFLINE;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getIntro() {
        return intro;
    }

    public String getCore() {
        return core;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public String getMode() {
        return mode;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getPlayers() {
        return players;
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isNoGuest() {
        return noGuest;
    }

    public boolean isNeedEmail() {
        return needEmail;
    }

    public String getQqGroupCode() {
        return qqGroupCode;
    }

    public String getQqGroupIdKey() {
        return qqGroupIdKey;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public boolean isPluginListVisible() {
        return pluginListVisible;
    }

    public boolean isModListVisible() {
        return modListVisible;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public boolean isOnline() {
        return status == RoomStatus.ONLINE;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public LocalDateTime getTopExpiresAt() {
        return topExpiresAt;
    }

    /** 购买置顶卡时同步；传 null 表示清除（定时任务清理过期记录时用） */
    public void setTopExpiresAt(LocalDateTime topExpiresAt) {
        this.topExpiresAt = topExpiresAt;
    }

    public LocalDateTime getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }
}