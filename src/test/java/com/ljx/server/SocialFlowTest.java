package com.ljx.server;

import com.ljx.server.auth.dto.AuthDtos.AccountDto;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.commerce.ScoreEventType;
import com.ljx.server.commerce.ScoreService;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.api.PageResult;
import com.ljx.server.room.dto.RoomDtos.CreateRoomRequest;
import com.ljx.server.room.dto.RoomDtos.HeartbeatRequest;
import com.ljx.server.room.dto.RoomDtos.JoinResponse;
import com.ljx.server.room.dto.RoomDtos.RoomDetailDto;
import com.ljx.server.room.dto.RoomDtos.RoomSummaryDto;
import com.ljx.server.social.dto.SocialDtos.FavoriteStateDto;
import com.ljx.server.ws.LobbyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** B3：收藏 / 足迹、互动分数与等级、大厅 WebSocket 推送 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SocialFlowTest {

    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AccountDto>> ACCOUNT =
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
    private static final ParameterizedTypeReference<ApiResponse<FavoriteStateDto>> FAVORITE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PageResult<RoomSummaryDto>>> PAGE =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ScoreService scoreService;

    @Autowired
    private EmailBindingRepository emailBindingRepository;

    @Test
    void favoriteToggleIsIdempotentAndUpdatesCount() {
        String owner = registerVerified("fav_owner").accessToken();
        String guest = registerVerified("fav_guest").accessToken();
        long roomId = createRoom(owner, "Favorite Room", 10);

        assertThat(detail(guest, roomId).favorited()).isFalse();
        assertThat(detail(guest, roomId).favoriteCount()).isZero();

        // 收藏 → 按钮态与计数同步
        var added = favorite(HttpMethod.POST, guest, roomId);
        assertThat(added.favorited()).isTrue();
        assertThat(added.favoriteCount()).isEqualTo(1);
        assertThat(detail(guest, roomId).favorited()).isTrue();

        // 重复收藏幂等：计数不翻倍
        assertThat(favorite(HttpMethod.POST, guest, roomId).favoriteCount()).isEqualTo(1);

        // 收藏视图能看到该房间
        assertThat(lobbyIds(guest, "view=favorite&size=100")).contains(roomId);

        // 取消收藏幂等：未收藏时再次取消仍成功
        var removed = favorite(HttpMethod.DELETE, guest, roomId);
        assertThat(removed.favorited()).isFalse();
        assertThat(removed.favoriteCount()).isZero();
        assertThat(favorite(HttpMethod.DELETE, guest, roomId).favoriteCount()).isZero();
        assertThat(detail(guest, roomId).favorited()).isFalse();
        assertThat(lobbyIds(guest, "view=favorite&size=100")).doesNotContain(roomId);

        // 不存在的房间
        assertThat(call(HttpMethod.POST, "/api/rooms/999999/favorite", guest, null, FAVORITE)
                .getBody().code()).isEqualTo(1301);
    }

    @Test
    void footprintCanBeRemovedAndCleared() {
        String owner = registerVerified("fp_owner").accessToken();
        String guest = registerVerified("fp_guest").accessToken();
        long first = createRoom(owner, "Footprint A", 10);
        long second = createRoom(owner, "Footprint B", 10);
        heartbeat(owner, first, 1);
        heartbeat(owner, second, 1);

        join(guest, first);
        join(guest, second);
        assertThat(lobbyIds(guest, "view=history&size=100")).contains(first, second);

        // 移除单条足迹
        assertThat(call(HttpMethod.DELETE, "/api/me/footprint/" + first, guest, null, VOID)
                .getBody().code()).isZero();
        assertThat(lobbyIds(guest, "view=history&size=100")).contains(second).doesNotContain(first);

        // 再次移除已不存在的足迹幂等成功
        assertThat(call(HttpMethod.DELETE, "/api/me/footprint/" + first, guest, null, VOID)
                .getBody().code()).isZero();

        // 清空足迹
        assertThat(call(HttpMethod.DELETE, "/api/me/footprint", guest, null, VOID).getBody().code()).isZero();
        assertThat(lobbyIds(guest, "view=history&size=100")).isEmpty();

        // 不存在的房间
        assertThat(call(HttpMethod.DELETE, "/api/me/footprint/999999", guest, null, VOID)
                .getBody().code()).isEqualTo(1301);
    }

    @Test
    void scoreAccruesFromEventsAndRaisesLevelAndPackageQuota() {
        TokenResponse owner = registerVerified("score_owner");
        String token = owner.accessToken();
        String guest = registerVerified("score_guest").accessToken();
        long ownerId = owner.account().id();

        // 注册时 LV0：客户端包上限 5 MB；容量由房主自设，不受等级限制
        assertThat(me(token).level()).isZero();
        assertThat(me(token).quotas()).containsEntry("CLIENT_PKG_MB", 5);
        assertThat(me(token).quotas()).containsEntry("PLAYER_CAP", Integer.MAX_VALUE);

        // 创建房间 +10
        long roomId = createRoom(token, "Score Room", 10);
        assertThat(me(token).score()).isEqualTo(10);

        // 他人加入 +1、他人收藏 +3
        heartbeat(token, roomId, 1);
        join(guest, roomId);
        favorite(HttpMethod.POST, guest, roomId);
        assertThat(me(token).score()).isEqualTo(14);

        // 自己收藏自己的房间不计分
        favorite(HttpMethod.POST, token, roomId);
        assertThat(me(token).score()).isEqualTo(14);

        // 继续攒经验直到升级。
        // 阈值由后台在 level_config 里配置，所以这里**不写死具体分数**，而是循环加到升级为止
        // —— 配置改了这条用例依然成立（P0 修复后 level_config 是等级的唯一真源）。
        int guard = 0;
        while (me(token).level() == 0 && guard++ < 100) {
            scoreService.award(ownerId, ScoreEventType.ROOM_CREATED);
        }
        AccountDto account = me(token);
        assertThat(account.level()).as("攒够配置的阈值后应升到 1 级").isEqualTo(1);
        assertThat(account.score())
                .as("升级时扣掉该级所需经验，score 是「当前等级内的进度」而不是累计总分")
                .isLessThan(100);
        // 升级后按 level_config 刷新客户端包上限（LV0 的 5MB → LV1 的 10MB）
        assertThat(account.quotas()).containsEntry("CLIENT_PKG_MB", 10);
        assertThat(account.quotas()).containsEntry("PLAYER_CAP", Integer.MAX_VALUE);
    }

    @Test
    void lobbyBroadcastsRoomStatus() throws Exception {
        String owner = registerVerified("ws_owner").accessToken();
        long roomId = createRoom(owner, "Broadcast Room", 10);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/lobby",
                new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);

        BlockingQueue<LobbyEvent> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/lobby", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return LobbyEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((LobbyEvent) payload);
            }
        });

        try {
            // 首次心跳播 ONLINE；若订阅尚未生效则改人数再触发一次 PLAYERS，保证断言不依赖时序
            LobbyEvent event = null;
            for (int i = 0; i < 10 && event == null; i++) {
                heartbeat(owner, roomId, i + 1);
                event = received.poll(500, TimeUnit.MILLISECONDS);
            }
            assertThat(event).isNotNull();
            assertThat(event.roomId()).isEqualTo(roomId);
            assertThat(event.online()).isTrue();
            assertThat(event.type()).isIn(LobbyEvent.Type.ONLINE, LobbyEvent.Type.PLAYERS);
            assertThat(event.capacity()).isEqualTo(10);
        } finally {
            session.disconnect();
            client.stop();
        }
    }

    // ---------- helpers ----------

    private AccountDto me(String token) {
        var res = call(HttpMethod.GET, "/api/me", token, null, ACCOUNT);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private RoomDetailDto detail(String token, long roomId) {
        var res = call(HttpMethod.GET, "/api/rooms/" + roomId, token, null, DETAIL);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private FavoriteStateDto favorite(HttpMethod method, String token, long roomId) {
        var res = call(method, "/api/rooms/" + roomId + "/favorite", token, null, FAVORITE);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private void join(String token, long roomId) {
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", token, null, JOIN)
                .getBody().code()).isZero();
    }

    private void heartbeat(String token, long roomId, int players) {
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", token,
                new HeartbeatRequest(players, 60, List.of()), VOID).getBody().code()).isZero();
    }

    private long createRoom(String token, String name, int capacity) {
        var res = call(HttpMethod.POST, "/api/rooms", token,
                new CreateRoomRequest(name, "intro", "Paper", "1.21.1", "生存", capacity,
                        false, false, false, null), DETAIL);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().id();
    }

    /** 带「禁止游客 / 需要邮箱」开关建房 */
    private long createRoom(String token, String name, boolean noGuest, boolean needEmail) {
        var res = call(HttpMethod.POST, "/api/rooms", token,
                new CreateRoomRequest(name, "intro", "Paper", "1.21.1", "生存", 5,
                        false, noGuest, needEmail, null), DETAIL);
        assertThat(res.getBody().code()).as("建房应成功（房主已绑邮箱）").isZero();
        return res.getBody().data().id();
    }

    /** 取一个访客（匿名）身份：与客户端"未登录自动申请访客"是同一条路径 */
    private String guestToken() {
        var res = call(HttpMethod.POST, "/api/auth/guest", null, null, TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().accessToken();
    }

    /** 进入房间（presence）：只查上锁与两个准入开关，不查房间是否在线 */
    private ResponseEntity<ApiResponse<Void>> enterRoom(String token, long roomId) {
        return call(HttpMethod.PUT, "/api/rooms/" + roomId + "/presence", token, null, VOID);
    }

    private List<Long> lobbyIds(String token, String query) {
        var res = call(HttpMethod.GET, "/api/rooms?" + query, token, null, PAGE);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().items().stream().map(RoomSummaryDto::id).toList();
    }

    private TokenResponse register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register", null,
                new RegisterRequest(username, "password123"), TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    /** 注册并直接写入已验证的邮箱绑定，用于通过「创建房间需绑定邮箱」门槛 */
    private TokenResponse registerVerified(String username) {
        TokenResponse token = register(username);
        EmailBinding binding = new EmailBinding(token.account().id());
        binding.markVerified(username + "@example.com");
        emailBindingRepository.save(binding);
        return token;
    }

    private <T> ResponseEntity<ApiResponse<T>> call(HttpMethod method, String path, String token,
                                                    Object body,
                                                    ParameterizedTypeReference<ApiResponse<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    /**
     * 「禁止游客」与「需要邮箱」是**两个独立开关**，不能是同一个行为。
     * <p>
     * 历史上两者被写成同一个分支（都走邮箱校验），房主勾哪个都一样，语义重叠。
     * 引入匿名账号标记后改成：
     *   * 禁止游客 → 只要"未登录/匿名账号"就拒（1307）；正式账号即使没绑邮箱也能进；
     *   * 需要邮箱 → 不论身份，都得绑了邮箱（1305）。
     */
    @Test
    void noGuestAndNeedEmailAreIndependentGates() {
        String owner = registerVerified("social_gate_owner").accessToken();

        // 房主建两种房间
        long roomNoGuest = createRoom(owner, "禁止游客房", true, false);
        long roomNeedEmail = createRoom(owner, "需要邮箱房", false, true);
        long roomBoth = createRoom(owner, "两者都要", true, true);

        // ① 访客（未登录时自动创建的匿名身份）三种房都进不去
        String guest = guestToken();
        assertThat(enterRoom(guest, roomNoGuest).getBody().code())
                .as("访客进禁止游客房应返回 1307").isEqualTo(1307);
        assertThat(enterRoom(guest, roomNeedEmail).getBody().code())
                .as("访客进需要邮箱房应返回 1305").isEqualTo(1305);
        assertThat(enterRoom(guest, roomBoth).getBody().code()).isEqualTo(1307);

        // ② 正式账号（**未绑邮箱**）：禁止游客房能进，需要邮箱房不能进
        String plainUser = register("social_gate_plain").accessToken();
        assertThat(enterRoom(plainUser, roomNoGuest).getBody().code())
                .as("正式账号即使没绑邮箱也应能进「禁止游客」房 —— 这正是与「需要邮箱」的区分点")
                .isZero();
        assertThat(enterRoom(plainUser, roomNeedEmail).getBody().code())
                .as("没绑邮箱进需要邮箱房应 1305").isEqualTo(1305);

        // ③ 房主本人不受两个开关限制
        assertThat(enterRoom(owner, roomNoGuest).getBody().code()).isZero();
        assertThat(enterRoom(owner, roomNeedEmail).getBody().code()).isZero();
    }
}
