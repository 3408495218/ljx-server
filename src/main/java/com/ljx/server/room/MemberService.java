package com.ljx.server.room;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.room.dto.RoomDtos.MemberDto;
import com.ljx.server.room.dto.RoomDtos.PresenceResponse;
import com.ljx.server.room.entity.Room;
import com.ljx.server.room.entity.RoomMember;
import com.ljx.server.room.entity.RoomMemberId;
import com.ljx.server.room.repository.RoomMemberRepository;
import com.ljx.server.room.repository.RoomRepository;
import com.ljx.server.ws.LobbyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 房间成员：谁「进入了房间」。
 * <p>
 * 与「服务端在线玩家」是两个正交概念——<b>平台看不到房主的服务端</b>，只能由每个客户端
 * 自己上报「我在这个房间里」（{@code PUT /api/rooms/{id}/presence}）。
 * 成员数物化写入 {@code room.players}，因此大厅卡片、排序、广播、前端全部沿用原字段、零改动。
 * <p>
 * 成员是否「已进入游戏」，由「成员名 ∈ 房主上报的服务端在线名单」判定（启发式口径）。
 */
@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final RoomMemberRepository memberRepository;
    private final RoomRepository roomRepository;
    private final AccountRepository accountRepository;
    private final RoomAccessPolicy accessPolicy;
    private final RoomMemberRegistrar registrar;
    private final ApplicationEventPublisher eventPublisher;

    public MemberService(RoomMemberRepository memberRepository,
                         RoomRepository roomRepository,
                         AccountRepository accountRepository,
                         RoomAccessPolicy accessPolicy,
                         RoomMemberRegistrar registrar,
                         ApplicationEventPublisher eventPublisher) {
        this.memberRepository = memberRepository;
        this.roomRepository = roomRepository;
        this.accountRepository = accountRepository;
        this.accessPolicy = accessPolicy;
        this.registrar = registrar;
        this.eventPublisher = eventPublisher;
    }

    /** 取未删除房间，不存在抛 1301（与 RoomService.findRoom 同口径） */
    private Room findRoom(Long roomId) {
        return roomRepository.findByIdAndDeletedAtIsNull(roomId)
                .orElseThrow(() -> new BizException(ErrorCode.ROOM_NOT_FOUND));
    }

    /**
     * 进入房间 / 续期（幂等）。房主与玩家走同一套逻辑：房主也必须"进入房间"才算成员。
     * 已存在则只刷 last_seen，不重复计数、不触发广播。
     */
    @Transactional
    public PresenceResponse enter(Long accountId, Long roomId) {
        Room room = findRoom(roomId);
        // 只校验上锁与邮箱门槛：房间离线也要能进（人数随之 +1），在线校验留给 POST /join
        accessPolicy.checkEnterable(room, accountId);

        LocalDateTime now = LocalDateTime.now();
        RoomMemberId id = new RoomMemberId(roomId, accountId);
        RoomMember existing = memberRepository.findById(id).orElse(null);
        if (existing != null) {
            existing.renew(now);
        } else {
            ensureSeat(room, accountId);
            // 并发下可能被另一个请求抢先插入（React StrictMode 会把挂载 effect 跑两遍，
            // 两个 PUT /presence 同时到达）→ 交给独立事务插入：撞主键只回滚内层事务，
            // 外层按"已在房间"继续，绝不在这里抛异常
            if (registrar.tryInsert(roomId, accountId, now)) {
                log.info("房间 {} 成员进入：账号 {}（{}）", roomId, accountId, room.getName());
                broadcastPlayers(room);
            }
        }
        // 注意：这里不写 room.players——成员行的插入与其人数校准都在 RoomMemberRegistrar
        // 的独立事务内完成，调用方事务只做读取，避免两个事务在同一个持久化上下文上纠缠。
        // 续期路径也顺带校准一次，历史脏数据（并发回滚 / 异常中断）由此自愈。
        if (existing != null) {
            int before = room.getPlayers();
            syncMemberCount(room);
            if (room.getPlayers() != before) {
                broadcastPlayers(room);
            }
        }
        return view(roomId);
    }

    /** 主动离开（幂等）。房间已被删除时抛 1301，前端应容错忽略。 */
    @Transactional
    public void leave(Long accountId, Long roomId) {
        Room room = findRoom(roomId);
        RoomMemberId id = new RoomMemberId(roomId, accountId);
        memberRepository.findById(id).ifPresent(member -> {
            memberRepository.delete(member);
            log.info("房间 {} 成员离开：账号 {}（{}）", roomId, accountId, room.getName());
            syncMemberCount(room);
            broadcastPlayers(room);
        });
    }

    /**
     * 超时清理：客户端崩溃 / 断网 / 强杀时不会有离开请求，靠 last_seen 兜底。
     * 与房间心跳下线同构：先取出待删行 → 批量删 → 受影响房间逐个重算并广播。
     */
    @Transactional
    public int expireStale(Duration timeout) {
        LocalDateTime threshold = LocalDateTime.now().minus(timeout);
        List<RoomMember> stale = memberRepository.findByLastSeenBefore(threshold);
        if (stale.isEmpty()) {
            return 0;
        }
        Set<Long> roomIds = stale.stream()
                .map(member -> member.getId().getRoomId())
                .collect(Collectors.toSet());
        memberRepository.deleteAll(stale);
        for (Long roomId : roomIds) {
            roomRepository.findById(roomId).ifPresent(room -> {
                syncMemberCount(room);
                broadcastPlayers(room);
            });
        }
        log.info("room members expired: {} rows across {} rooms", stale.size(), roomIds.size());
        return stale.size();
    }

    /** 房间被删除时清理成员行，避免残留占位 */
    @Transactional
    public void purgeRoom(Long roomId) {
        memberRepository.deleteByIdRoomId(roomId);
    }

    /** 房间成员数（实时统计）：读路径一律以此为准，物化值只服务排序与广播 */
    public int countOf(Long roomId) {
        return memberRepository.countByIdRoomId(roomId);
    }

    /** 批量成员数：房间 id → 人数；无成员的房间不会出现在结果里（调用方用 getOrDefault 兜底 0） */
    public Map<Long, Integer> countByRoomIds(Collection<Long> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : memberRepository.countByRoomIds(roomIds)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** 成员视图：房主在前，其余按进入顺序 */
    public PresenceResponse view(Long roomId) {
        Room room = findRoom(roomId);
        List<RoomMember> rows = memberRepository.findByIdRoomIdOrderByJoinedAtAsc(roomId);
        Map<Long, Account> accounts = accountRepository
                .findAllById(rows.stream().map(member -> member.getId().getAccountId()).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        // 服务端在线名单：房主心跳整体覆盖，用于判定"是否已进入游戏"
        Set<String> onlineNames = new HashSet<>(room.listPlayerNames());

        List<MemberDto> members = rows.stream()
                .map(member -> {
                    Long memberId = member.getId().getAccountId();
                    Account account = accounts.get(memberId);
                    String username = account == null ? "未知" : account.getUsername();
                    return new MemberDto(
                            memberId,
                            username,
                            account == null ? 0 : account.getVipLevel(),
                            account == null ? 0 : account.getLevel(),
                            onlineNames.contains(username),
                            room.getOwnerId().equals(memberId),
                            member.getJoinedAt());
                })
                .sorted(Comparator.comparing(MemberDto::owner).reversed()
                        .thenComparing(MemberDto::joinedAt))
                .toList();
        return new PresenceResponse(members, members.size(), room.getCapacity());
    }

    /** 成员数变化后广播大厅人数（AFTER_COMMIT 才真正推送，回滚不外泄） */
    private void broadcastPlayers(Room room) {
        eventPublisher.publishEvent(new LobbyEvent(LobbyEvent.Type.PLAYERS, room.getId(), room.getName(),
                countOf(room.getId()), room.getCapacity(), room.isOnline(),
                room.getCore(), room.getMcVersion(), room.getMode()));
    }

    /** 容量按「非房主成员数」卡：房主不占位（自己建的房必须能进） */
    private void ensureSeat(Room room, Long accountId) {
        if (room.getOwnerId().equals(accountId)) {
            return;
        }
        int guests = memberRepository.countGuests(room.getId(), room.getOwnerId());
        if (guests >= room.getCapacity()) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED);
        }
    }

    /**
     * 把成员数物化进 room.players：列表、排序、广播、前端都沿用这个字段。
     * 人数变化时记一行日志——排查「人数不对」时，这一行能直接回答"后端到底有没有变"。
     */
    private void syncMemberCount(Room room) {
        int before = room.getPlayers();
        int now = memberRepository.countByIdRoomId(room.getId());
        room.setMemberCount(now);
        if (before != now) {
            log.info("房间 {} 人数变更：{} -> {}（{}）", room.getId(), before, now, room.getName());
        }
    }
}
