package com.ljx.server.room;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.commerce.ScoreEventType;
import com.ljx.server.commerce.ScoreService;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.quota.QuotaService;
import com.ljx.server.quota.QuotaType;
import com.ljx.server.room.dto.RoomDtos.CreateRoomRequest;
import com.ljx.server.room.dto.RoomDtos.HeartbeatRequest;
import com.ljx.server.room.dto.RoomDtos.JoinResponse;
import com.ljx.server.room.dto.RoomDtos.RoomDetailDto;
import com.ljx.server.room.dto.RoomDtos.RoomSummaryDto;
import com.ljx.server.room.dto.RoomDtos.UpdateRoomRequest;
import com.ljx.server.room.entity.Room;
import com.ljx.server.room.entity.RoomStatus;
import com.ljx.server.room.repository.RoomRepository;
import com.ljx.server.social.entity.JoinRecord;
import com.ljx.server.social.repository.JoinRecordRepository;
import com.ljx.server.social.repository.RoomFavoriteRepository;
import com.ljx.server.ws.LobbyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final AccountRepository accountRepository;
    private final RoomAccessPolicy accessPolicy;
    private final MemberService memberService;
    private final RoomFavoriteRepository favoriteRepository;
    private final JoinRecordRepository joinRecordRepository;
    private final QuotaService quotaService;
    private final ScoreService scoreService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.ljx.server.commerce.repository.VipPlanRepository vipPlanRepository;

    public RoomService(RoomRepository roomRepository,
                       AccountRepository accountRepository,
                       RoomAccessPolicy accessPolicy,
                       MemberService memberService,
                       RoomFavoriteRepository favoriteRepository,
                       JoinRecordRepository joinRecordRepository,
                       QuotaService quotaService,
                       ScoreService scoreService,
                       AuditService auditService,
                       ApplicationEventPublisher eventPublisher,
                       com.ljx.server.commerce.repository.VipPlanRepository vipPlanRepository) {
        this.roomRepository = roomRepository;
        this.accountRepository = accountRepository;
        this.accessPolicy = accessPolicy;
        this.memberService = memberService;
        this.favoriteRepository = favoriteRepository;
        this.joinRecordRepository = joinRecordRepository;
        this.quotaService = quotaService;
        this.scoreService = scoreService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.vipPlanRepository = vipPlanRepository;
    }

    @Transactional
    public RoomDetailDto create(Long accountId, CreateRoomRequest request) {
        // 访客账号不必绑邮箱：本软件定位是开服器，未登录也要能开服。
        // 访客本身就是匿名身份，要求绑邮箱等于变相强制登录（而且它没有真实邮箱可绑）。
        boolean guest = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .map(Account::isAnonymous)
                .orElse(false);
        if (!guest) {
            accessPolicy.requireVerifiedEmail(accountId, ErrorCode.EMAIL_REQUIRED_FOR_CREATE);
        }
        checkCapacity(accountId, request.capacity());
        Room room = new Room(accountId, request.name().trim(), blankToNull(request.intro()),
                request.core().trim(), request.mcVersion().trim(), defaultMode(request.mode()),
                request.capacity(),
                request.locked(), request.noGuest(), request.needEmail(), blankToNull(request.coverUrl()));
        roomRepository.save(room);
        scoreService.award(accountId, ScoreEventType.ROOM_CREATED);
        auditService.record(accountId, AuditAction.ROOM_CREATE, room.getName(), true);
        return detail(accountId, room.getId());
    }

    @Transactional
    public RoomDetailDto update(Long accountId, Long roomId, UpdateRoomRequest request) {
        Room room = requireOwned(accountId, roomId);
        if (request.capacity() != null) {
            checkCapacity(accountId, request.capacity());
        }
        room.update(blankToNull(request.name()), blankToNull(request.intro()),
                blankToNull(request.core()), blankToNull(request.mcVersion()), blankToNull(request.mode()),
                request.capacity(), request.locked(), request.noGuest(), request.needEmail(),
                blankToNull(request.coverUrl()));
        room.setPublicAddress(request.host(), request.port());
        room.setListVisibility(request.pluginListVisible(), request.modListVisible());
        return detail(accountId, roomId);
    }

    @Transactional
    public void delete(Long accountId, Long roomId) {
        Room room = requireOwned(accountId, roomId);
        room.softDelete();
        memberService.purgeRoom(roomId);
        auditService.record(accountId, AuditAction.ROOM_DELETE, room.getName(), true);
    }

    /**
     * 容量由服主自行设置，不与 VIP 等级挂钩，服务端不做越界拦截。
     * 仍走 {@link QuotaService} 是为了保留唯一校验入口（{@link QuotaType#PLAYER_CAP} 当前为「不限」）；
     * 若产品后续要定上限，只改 QuotaType 即可，调用点不动。
     */
    private void checkCapacity(Long accountId, int capacity) {
        quotaService.check(accountId, QuotaType.PLAYER_CAP, capacity);
    }

    /**
     * 「我的游戏」：当前账号名下的房间。
     * 与大厅口径保持一致：VIP 判过期、带边框资源键与置顶标记。
     */
    public List<RoomSummaryDto> myRooms(Long accountId) {
        int vip = effectiveVipLevel(accountRepository.findByIdAndDeletedAtIsNull(accountId).orElse(null));
        Map<Integer, String> borderByLevel = vipPlanRepository.findAllByOrderByLevelAsc().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.ljx.server.commerce.entity.VipPlan::getLevel,
                        plan -> plan.getBorderUrl() == null ? "" : plan.getBorderUrl()));
        List<Room> rooms = roomRepository.findByOwnerIdAndDeletedAtIsNullOrderByIdDesc(accountId);
        Map<Long, Integer> memberCounts = memberService.countByRoomIds(rooms.stream().map(Room::getId).toList());
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return rooms.stream()
                .map(room -> new RoomSummaryDto(room.getId(), room.getName(), room.getCoverUrl(),
                        memberCounts.getOrDefault(room.getId(), 0), room.getCapacity(),
                        vip, borderByLevel.getOrDefault(vip, ""),
                        room.getTopExpiresAt() != null && room.getTopExpiresAt().isAfter(now),
                        room.isOnline(), room.getCore(), room.getMcVersion(), room.getMode()))
                .toList();
    }

    /** 生效 VIP 档位：过期即按普通用户（与 CommerceService / LobbyService 同一口径） */
    private int effectiveVipLevel(Account account) {
        if (account == null || account.getVipLevel() <= 0) {
            return 0;
        }
        java.time.LocalDateTime expiresAt = account.getVipExpiresAt();
        return expiresAt != null && expiresAt.isAfter(java.time.LocalDateTime.now())
                ? account.getVipLevel()
                : 0;
    }

    public RoomDetailDto detail(Long accountId, Long roomId) {
        Room room = findRoom(roomId);
        Account owner = accountRepository.findByIdAndDeletedAtIsNull(room.getOwnerId()).orElse(null);
        return new RoomDetailDto(
                room.getId(), room.getName(), room.getIntro(), room.getCore(), room.getMcVersion(),
                room.getMode(),
                room.getCapacity(), memberService.countOf(roomId), room.isLocked(), room.isNoGuest(), room.isNeedEmail(),
                room.getQqGroupCode(), room.getQqGroupIdKey(),
                room.getCoverUrl(), room.isOnline(), room.getHost(), room.getPort(),
                favoriteRepository.countByRoomId(roomId),
                owner == null ? "未知" : owner.getUsername(),
                owner == null ? 0 : owner.getVipLevel(),
                room.getOwnerId().equals(accountId),
                favoriteRepository.existsByRoomIdAndAccountId(roomId, accountId),
                room.isPluginListVisible(), room.isModListVisible(), room.listPlayerNames(),
                memberService.view(roomId).members());
    }

    @Transactional
    public void heartbeat(Long accountId, Long roomId, HeartbeatRequest request) {
        Room room = requireOwned(accountId, roomId);
        boolean wasOnline = room.isOnline();
        List<String> previousNames = room.listPlayerNames();
        room.heartbeat(request.playerNames(), LocalDateTime.now());
        // 上线播 ONLINE；在线期间只有「服务端在线名单」变化才播 PLAYERS。
        // 房间人数已改由成员进出驱动（MemberService），心跳不再影响它，
        // 否则每 30 秒都会产生一次无意义广播。
        if (!wasOnline) {
            log.info("房间 {} 上线（{}）", roomId, room.getName());
            broadcast(room, LobbyEvent.Type.ONLINE);
        } else if (!room.listPlayerNames().equals(previousNames)) {
            // 服务端在线名单变化：只在真变化时记一行，30 秒一次的常规心跳不会刷屏
            log.info("房间 {} 服务端在线名单变更：{} 人 {}", roomId,
                    room.listPlayerNames().size(), room.listPlayerNames());
            broadcast(room, LobbyEvent.Type.PLAYERS);
        }
    }

    /** 点亮 / 取消点亮QQ加群：群号与组件凭据整体覆盖，传空即取消 */
    @Transactional
    public RoomDetailDto setQqGroup(Long accountId, Long roomId, String code, String idKey) {
        requireOwned(accountId, roomId).setQqGroup(blankToNull(code), blankToNull(idKey));
        return detail(accountId, roomId);
    }

    /** 玩家加入：上锁 / 在线 / 邮箱门槛校验，成功后写入足迹并下发房主公网地址 */
    @Transactional
    public JoinResponse join(Long accountId, Long roomId) {
        Room room = findRoom(roomId);
        accessPolicy.checkJoinable(room, accountId);
        joinRecordRepository.save(new JoinRecord(roomId, accountId));
        // 他人加入给房主加分；房主自己进入不计分
        if (!room.getOwnerId().equals(accountId)) {
            scoreService.award(room.getOwnerId(), ScoreEventType.JOINED_BY_OTHER);
        }
        return new JoinResponse(room.getHost(), room.getPort());
    }

    /** 心跳超时下线；由 RoomHeartbeatScheduler 周期调用 */
    @Transactional
    public int expireStale(Duration timeout) {
        LocalDateTime now = LocalDateTime.now();
        List<LobbyEvent> offlineEvents = roomRepository
                .findStaleOnline(RoomStatus.ONLINE, now.minus(timeout)).stream()
                .map(room -> event(room, LobbyEvent.Type.OFFLINE))
                .toList();
        if (offlineEvents.isEmpty()) {
            return 0;
        }
        int updated = roomRepository.markStaleOffline(RoomStatus.ONLINE, RoomStatus.OFFLINE, now.minus(timeout), now);
        offlineEvents.forEach(eventPublisher::publishEvent);
        return updated;
    }

    /** 状态变化事件在事务提交后才由 LobbyBroadcaster 推送（见 ws 包） */
    private void broadcast(Room room, LobbyEvent.Type type) {
        eventPublisher.publishEvent(event(room, type));
    }

    private static LobbyEvent event(Room room, LobbyEvent.Type type) {
        return new LobbyEvent(type, room.getId(), room.getName(), room.getPlayers(), room.getCapacity(),
                type != LobbyEvent.Type.OFFLINE, room.getCore(), room.getMcVersion(), room.getMode());
    }

    /** 取未删除房间，不存在抛 1301；供房间子资源（内容清单等）读取房间级开关 */
    public Room findRoom(Long roomId) {
        return roomRepository.findByIdAndDeletedAtIsNull(roomId)
                .orElseThrow(() -> new BizException(ErrorCode.ROOM_NOT_FOUND));
    }

    /** 校验房间归属，非房主抛 1302；供房间子资源（已装内容、快照）复用 */
    public Room requireOwned(Long accountId, Long roomId) {
        Room room = findRoom(roomId);
        if (!room.getOwnerId().equals(accountId)) {
            throw new BizException(ErrorCode.ROOM_FORBIDDEN);
        }
        return room;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultMode(String mode) {
        String value = blankToNull(mode);
        return value == null ? "生存" : value;
    }
}