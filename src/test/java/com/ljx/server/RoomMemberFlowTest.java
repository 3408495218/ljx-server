package com.ljx.server;

import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.room.MemberService;
import com.ljx.server.room.dto.RoomDtos.CreateRoomRequest;
import com.ljx.server.room.dto.RoomDtos.HeartbeatRequest;
import com.ljx.server.room.dto.RoomDtos.JoinResponse;
import com.ljx.server.room.dto.RoomDtos.MemberDto;
import com.ljx.server.room.dto.RoomDtos.PresenceResponse;
import com.ljx.server.room.dto.RoomDtos.RoomDetailDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 房间成员语义：<b>房间人数 = 进入房间的人数</b>（房主与玩家同一套逻辑），
 * 与「服务端在线玩家」（房主心跳上报的 playerNames）是正交概念。
 * <p>
 * 成员是否「已进入游戏」= 成员名出现在服务端在线名单里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomMemberFlowTest {

    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<RoomDetailDto>> DETAIL =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<JoinResponse>> JOIN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PresenceResponse>> PRESENCE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EmailBindingRepository emailBindingRepository;

    @Autowired
    private MemberService memberService;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void memberCountIsIndependentFromServerOnlinePlayers() {
        String owner = registerVerified("member_owner");
        long roomId = create(owner, "Member Room", 2).getBody().data().id();

        // 房主心跳上线：只刷新在线态与在线名单，不改变房间人数
        heartbeat(owner, roomId, List.of());
        var online = detail(owner, roomId).getBody().data();
        assertThat(online.online()).isTrue();
        assertThat(online.players()).isZero();
        assertThat(online.members()).isEmpty();

        // 房主「进入房间」→ 成员 1；此时他还没进游戏
        assertThat(enter(owner, roomId).getBody().data().memberCount()).isEqualTo(1);
        var withOwner = detail(owner, roomId).getBody().data();
        assertThat(withOwner.players()).isEqualTo(1);
        assertThat(withOwner.members()).hasSize(1);
        assertThat(withOwner.members().get(0).owner()).isTrue();
        assertThat(withOwner.members().get(0).inGame()).isFalse();
    }

    @Test
    void ownerDoesNotTakeCapacitySlot() {
        String owner = registerVerified("slot_owner");
        String guest1 = registerVerified("slot_g1");
        String guest2 = registerVerified("slot_g2");
        String guest3 = registerVerified("slot_g3");

        long roomId = create(owner, "Slot Room", 2).getBody().data().id();
        heartbeat(owner, roomId, List.of());
        assertThat(enter(owner, roomId).getBody().code()).isZero();

        // 房主不占位：容量 2 仍可进两个玩家
        assertThat(enter(guest1, roomId).getBody().code()).isZero();
        assertThat(enter(guest2, roomId).getBody().code()).isZero();
        assertThat(detail(guest1, roomId).getBody().data().players()).isEqualTo(3);

        // 第三个玩家超员（1401）
        assertThat(enter(guest3, roomId).getBody().code()).isEqualTo(1401);
    }

    @Test
    void repeatedEnterDoesNotDuplicateAndKeepsJoinedAt() {
        String owner = registerVerified("dup_owner");
        String guest = registerVerified("dup_g1");
        long roomId = create(owner, "Dup Room", 5).getBody().data().id();
        heartbeat(owner, roomId, List.of());

        enter(owner, roomId);
        var first = enter(guest, roomId).getBody().data();
        LocalDateTime joinedAt = joinTimeOf(first, "dup_g1");

        // 续期（每 30 秒一次）不能重复计数，也不能把 joined_at 刷新成"刚加入"
        var again = enter(guest, roomId).getBody().data();
        assertThat(again.memberCount()).isEqualTo(2);
        // H2 的 TIMESTAMP 只到微秒，而首次返回的是内存里的纳秒值，比较时忽略纳秒
        assertThat(joinTimeOf(again, "dup_g1")).isEqualToIgnoringNanos(joinedAt);
    }

    @Test
    void inGameFollowsOwnerHeartbeatRoster() {
        String owner = registerVerified("ingame_owner");
        String guest = registerVerified("ingame_g1");
        long roomId = create(owner, "InGame Room", 5).getBody().data().id();
        heartbeat(owner, roomId, List.of());
        enter(owner, roomId);
        enter(guest, roomId);

        // 房主把 guest 报进服务端在线名单 → 该成员「游戏中」，未进游戏的人仍是「等待」
        heartbeat(owner, roomId, List.of("ingame_g1"));
        var entered = detail(guest, roomId).getBody().data();
        assertThat(inGameOf(entered, "ingame_g1")).isTrue();
        assertThat(inGameOf(entered, "ingame_owner")).isFalse();

        // 服务端名单清空 → 回到「等待」，但成员数不变
        heartbeat(owner, roomId, List.of());
        var after = detail(guest, roomId).getBody().data();
        assertThat(inGameOf(after, "ingame_g1")).isFalse();
        assertThat(after.players()).isEqualTo(2);
    }

    @Test
    void leaveAndTimeoutBothRemoveMembers() {
        String owner = registerVerified("leave_owner");
        String guest = registerVerified("leave_g1");
        long roomId = create(owner, "Leave Room", 5).getBody().data().id();
        heartbeat(owner, roomId, List.of());
        enter(owner, roomId);
        enter(guest, roomId);
        assertThat(detail(owner, roomId).getBody().data().players()).isEqualTo(2);

        // 主动离开，且幂等（重复调用不报错、不改变结果）
        assertThat(leave(guest, roomId).getBody().code()).isZero();
        assertThat(leave(guest, roomId).getBody().code()).isZero();
        assertThat(detail(owner, roomId).getBody().data().players()).isEqualTo(1);

        // 客户端崩溃 / 断网：负超时等价于「所有成员都已超时」
        assertThat(memberService.expireStale(Duration.ofSeconds(-1))).isPositive();
        var after = detail(owner, roomId).getBody().data();
        assertThat(after.players()).isZero();
        assertThat(after.members()).isEmpty();
    }

    @Test
    void softDeletedRoomIsNoLongerEnterable() {
        String owner = registerVerified("del_owner");
        String guest = registerVerified("del_g1");
        long roomId = create(owner, "Del Room", 5).getBody().data().id();
        heartbeat(owner, roomId, List.of());
        enter(guest, roomId);

        assertThat(call(HttpMethod.DELETE, "/api/rooms/" + roomId, owner, null, VOID)
                .getBody().code()).isZero();
        // 房间已删除：进入与离开都返回 1301（前端对离开失败应容错忽略）
        assertThat(enter(guest, roomId).getBody().code()).isEqualTo(1301);
        assertThat(leave(guest, roomId).getBody().code()).isEqualTo(1301);
    }

    /**
     * 并发重复进入必须幂等。
     * <p>
     * 回归背景：客户端在开发模式（React StrictMode）会把挂载的 effect 跑两遍，
     * 同一账号的 PUT /presence 会并发到达，此前"先查后插"会双双插入 → 一个主键冲突，
     * 整个事务回滚也把 room.players 的同步一起丢掉，表现为「成员列表有房主、人数却是 0」。
     */
    @Test
    void concurrentEnterIsIdempotent() throws Exception {
        String owner = registerVerified("conc_owner");
        long roomId = create(owner, "Concurrent Room", 5).getBody().data().id();
        heartbeat(owner, roomId, List.of());

        Long ownerId = accountIdOf("conc_owner");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    // 直接打 service：走 HTTP 时客户端可能把并发请求串行化，测不出真实竞争
                    start.await();
                    memberService.enter(ownerId, roomId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();   // 并发进入不允许抛异常（此前会因主键冲突 5000）
            }
        } finally {
            pool.shutdownNow();
        }

        var after = detail(owner, roomId).getBody().data();
        assertThat(after.members()).as("同一账号并发进入只算一个成员").hasSize(1);
        assertThat(after.players()).isEqualTo(1);
    }

    /** 读路径一致性：人数与成员列表永远同源，不能再出现"列表有人、人数为 0" */
    @Test
    void playerCountAlwaysMatchesMemberList() {
        String owner = registerVerified("match_owner");
        String guest = registerVerified("match_g1");
        long roomId = create(owner, "Match Room", 5).getBody().data().id();
        heartbeat(owner, roomId, List.of());

        List<Runnable> steps = List.of(
                () -> enter(owner, roomId),
                () -> enter(guest, roomId),
                () -> leave(guest, roomId),
                () -> enter(guest, roomId),
                () -> leave(owner, roomId));
        for (Runnable step : steps) {
            step.run();
            var d = detail(owner, roomId).getBody().data();
            assertThat(d.players())
                    .as("players 必须等于成员数")
                    .isEqualTo(d.members().size());
        }
    }

    /**
     * 房间离线时也要能「进入房间」。
     * <p>
     * 口径：只要有人进到房间，房间人数就实时 +1 —— 房间离线只代表"服务端没在跑、暂时玩不了"，
     * 不该拦着人进房间等。在线校验（1304）只属于真正要连服务端的 POST /join。
     */
    @Test
    void offlineRoomStillAcceptsMembers() {
        String owner = registerVerified("offline_owner");
        String guest = registerVerified("offline_g1");
        long roomId = create(owner, "Offline Room", 5).getBody().data().id();
        // 故意不发心跳：房间保持 OFFLINE

        assertThat(enter(guest, roomId).getBody().code()).as("离线房间也应能进入").isZero();
        var detail = detail(guest, roomId).getBody().data();
        assertThat(detail.online()).isFalse();
        assertThat(detail.players()).isEqualTo(1);
        assertThat(detail.members()).hasSize(1);

        // 但真正加入（拿房主地址去连服务端）仍要求房间在线
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN)
                .getBody().code()).isEqualTo(1304);
    }

    // ---------- 辅助 ----------

    private ResponseEntity<ApiResponse<PresenceResponse>> enter(String token, long roomId) {
        return call(HttpMethod.PUT, "/api/rooms/" + roomId + "/presence", token, null, PRESENCE);
    }

    private ResponseEntity<ApiResponse<Void>> leave(String token, long roomId) {
        return call(HttpMethod.DELETE, "/api/rooms/" + roomId + "/presence", token, null, VOID);
    }

    private void heartbeat(String token, long roomId, List<String> names) {
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", token,
                new HeartbeatRequest(names.size(), 60, names), VOID).getBody().code()).isZero();
    }

    private ResponseEntity<ApiResponse<RoomDetailDto>> detail(String token, long roomId) {
        return call(HttpMethod.GET, "/api/rooms/" + roomId, token, null, DETAIL);
    }

    private ResponseEntity<ApiResponse<RoomDetailDto>> create(String token, String name, int capacity) {
        var res = call(HttpMethod.POST, "/api/rooms", token,
                new CreateRoomRequest(name, "intro", "Paper", "1.21.1", "生存", capacity,
                        false, false, false, null), DETAIL);
        assertThat(res.getBody().code()).isZero();
        return res;
    }

    private String registerVerified(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register", null,
                new RegisterRequest(username, "password123"), TOKEN);
        assertThat(res.getBody().code()).isZero();
        TokenResponse token = res.getBody().data();
        EmailBinding binding = new EmailBinding(token.account().id());
        binding.markVerified(username + "@example.com");
        emailBindingRepository.save(binding);
        return token.accessToken();
    }

    private Long accountIdOf(String username) {
        return accountRepository.findByUsernameAndDeletedAtIsNull(username).orElseThrow().getId();
    }

    private static MemberDto memberOf(RoomDetailDto detail, String username) {
        return detail.members().stream()
                .filter(member -> member.username().equals(username))
                .findFirst()
                .orElseThrow();
    }

    private static boolean inGameOf(RoomDetailDto detail, String username) {
        return memberOf(detail, username).inGame();
    }

    private static LocalDateTime joinTimeOf(PresenceResponse presence, String username) {
        return presence.members().stream()
                .filter(member -> member.username().equals(username))
                .findFirst()
                .orElseThrow()
                .joinedAt();
    }

    private <T> ResponseEntity<ApiResponse<T>> call(HttpMethod method, String path, String token,
                                                    Object body,
                                                    ParameterizedTypeReference<ApiResponse<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}
