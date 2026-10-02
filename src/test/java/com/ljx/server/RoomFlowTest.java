package com.ljx.server;

import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.api.PageResult;
import com.ljx.server.content.dto.ContentDtos.ClientPackageDto;
import com.ljx.server.content.dto.ContentDtos.ReportRoomContentRequest;
import com.ljx.server.content.dto.ContentDtos.RoomContentDto;
import com.ljx.server.content.entity.ContentType;
import com.ljx.server.room.RoomService;
import com.ljx.server.room.dto.RoomDtos.CreateRoomRequest;
import com.ljx.server.room.dto.RoomDtos.HeartbeatRequest;
import com.ljx.server.room.dto.RoomDtos.JoinResponse;
import com.ljx.server.room.dto.RoomDtos.RoomDetailDto;
import com.ljx.server.room.dto.RoomDtos.RoomSummaryDto;
import com.ljx.server.room.dto.RoomDtos.UpdateRoomRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomFlowTest {

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
    private static final ParameterizedTypeReference<ApiResponse<PageResult<RoomSummaryDto>>> PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<RoomSummaryDto>>> MINE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<RoomContentDto>>> CONTENTS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<ClientPackageDto>> PKG =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RoomService roomService;

    @Autowired
    private EmailBindingRepository emailBindingRepository;

    @Test
    void myRoomsListsOwnedOnly() {
        String owner = registerVerified("mine_owner");
        String other = registerVerified("mine_other");

        // 新账号名下无房间 → 前端页签应停留在「创建游戏」
        assertThat(myRoomIds(owner)).isEmpty();

        long first = create(owner, "Mine Room A", 5).getBody().data().id();
        long second = create(owner, "Mine Room B", 6).getBody().data().id();
        create(other, "Other Room", 5);

        // 只返回自己的房间，新建优先
        assertThat(myRoomIds(owner)).containsExactly(second, first);
        assertThat(myRoomIds(other)).hasSize(1);

        // 软删除后不再出现在「我的游戏」
        assertThat(call(HttpMethod.DELETE, "/api/rooms/" + second, owner, null, VOID)
                .getBody().code()).isZero();
        assertThat(myRoomIds(owner)).containsExactly(first);
    }

    @Test
    void createRequiresVerifiedEmail() {
        String token = register("no_email_owner");
        var denied = call(HttpMethod.POST, "/api/rooms", token,
                new CreateRoomRequest("No Email Room", null, "Paper", "1.21.1", "生存", 5,
                        false, false, false, null), DETAIL);
        assertThat(denied.getBody().code()).isEqualTo(1306);
    }

    /** 请求体 JSON 畸形属于客户端参数问题，应返回 1001 而不是 5000 */
    @Test
    void malformedBodyReturnsValidationError() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var res = rest.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>("{\"username\": ", headers), VOID);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(1001);
    }

    @Test
    void createHeartbeatJoinAndLobbyViews() {
        String owner = registerVerified("room_owner_1");
        String guest = register("room_guest_1");

        long roomId = create(owner, "Test Room A", 8).getBody().data().id();

        var created = detail(guest, roomId).getBody().data();
        assertThat(created.mine()).isFalse();
        assertThat(created.ownerName()).isEqualTo("room_owner_1");
        assertThat(created.online()).isFalse();
        assertThat(created.favoriteCount()).isZero();

        // 容量由服主自设，不与 VIP 挂钩，服务端不设上限
        var bigCapacity = call(HttpMethod.POST, "/api/rooms", owner,
                new CreateRoomRequest("Big Capacity Room", "intro", "Paper", "1.21.1", "生存", 500,
                        false, false, false, null), DETAIL);
        assertThat(bigCapacity.getBody().code()).isZero();
        assertThat(bigCapacity.getBody().data().capacity()).isEqualTo(500);

        // 非房主不能心跳
        var forbiddenHeartbeat = call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", guest,
                new HeartbeatRequest(3, 60, List.of()), VOID);
        assertThat(forbiddenHeartbeat.getBody().code()).isEqualTo(1302);

        // 房主设置公网地址并心跳上线
        var address = call(HttpMethod.PUT, "/api/rooms/" + roomId, owner,
                new UpdateRoomRequest(null, null, null, null, null, null, null, null, null, null,
                        "203.0.113.7", 25565, null, null), DETAIL);
        assertThat(address.getBody().code()).isZero();
        assertThat(address.getBody().data().mine()).isTrue();

        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", owner,
                new HeartbeatRequest(5, 60, List.of("Drbiaodi", "Steve")), VOID).getBody().code()).isZero();

        var online = findInLobby(guest, "view=all", roomId);
        assertThat(online).isNotNull();
        assertThat(online.online()).isTrue();
        // 房间人数已改为「成员数」（进入房间才计数），心跳只负责服务端在线名单——详见 RoomMemberFlowTest
        assertThat(online.players()).isZero();
        assertThat(online.capacity()).isEqualTo(8);

        // 在线名单随心跳整体覆盖，玩家侧详情能看到真实名单
        assertThat(detail(guest, roomId).getBody().data().playerNames())
                .containsExactly("Drbiaodi", "Steve");
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", owner,
                new HeartbeatRequest(1, 60, List.of("Drbiaodi")), VOID).getBody().code()).isZero();
        assertThat(detail(guest, roomId).getBody().data().playerNames()).containsExactly("Drbiaodi");
        // 玩家全部退出后名单清空，不留空串占位
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", owner,
                new HeartbeatRequest(0, 60, List.of()), VOID).getBody().code()).isZero();
        assertThat(detail(guest, roomId).getBody().data().playerNames()).isEmpty();

        // 加入拿到房主公网地址，并写入足迹
        var joined = call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN);
        assertThat(joined.getBody().code()).isZero();
        assertThat(joined.getBody().data().host()).isEqualTo("203.0.113.7");
        assertThat(joined.getBody().data().port()).isEqualTo(25565);

        assertThat(lobbyIds(guest, "view=history&size=100")).contains(roomId);
        // 收藏接口在 B3，此时收藏视图不应包含该房间
        assertThat(lobbyIds(guest, "view=favorite&size=100")).doesNotContain(roomId);

        // 关键词筛选
        assertThat(lobbyIds(guest, "q=Test Room A&size=100")).contains(roomId);
        assertThat(lobbyIds(guest, "q=NoSuchRoomXyz&size=100")).doesNotContain(roomId);
        // 核心筛选
        assertThat(lobbyIds(guest, "core=Paper&size=100")).contains(roomId);
        assertThat(lobbyIds(guest, "core=Forge&size=100")).doesNotContain(roomId);
        // 玩法筛选
        assertThat(lobbyIds(guest, "mode=生存&size=100")).contains(roomId);
        assertThat(lobbyIds(guest, "mode=创造&size=100")).doesNotContain(roomId);
    }

    @Test
    void joinGatesAndOwnershipChecks() {
        String owner = registerVerified("gate_owner");
        String guest = register("gate_guest");
        long roomId = create(owner, "Gate Room", 6).getBody().data().id();

        // 未心跳 → 离线，加入被拒
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN)
                .getBody().code()).isEqualTo(1304);

        heartbeat(owner, roomId, 1);

        // 上锁
        putFlags(owner, roomId, true, false, false);
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN)
                .getBody().code()).isEqualTo(1303);

        // 需要绑定邮箱
        putFlags(owner, roomId, false, false, true);
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN)
                .getBody().code()).isEqualTo(1305);

        // 「禁止游客」与「需要邮箱」是**两个独立开关**（曾经被写成同一个分支，已修正）：
        //   * 禁止游客 → 只看"是不是匿名访客"；正式账号即使没绑邮箱也能进；
        //   * 需要邮箱 → 不论身份，都得先绑邮箱。
        // 这里的 guest 是**正式账号但没绑邮箱**，所以：
        putFlags(owner, roomId, false, true, false);
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN)
                .getBody().code())
                .as("正式账号进「禁止游客」房应放行（这就是它与「需要邮箱」的区别）")
                .isZero();

        putFlags(owner, roomId, false, true, true);
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", guest, null, JOIN)
                .getBody().code())
                .as("两个开关都开时，未绑邮箱的正式账号仍被拒（1305）")
                .isEqualTo(1305);

        // 房主自己不受门槛限制
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/join", owner, null, JOIN)
                .getBody().code()).isZero();

        // 非房主不能改、不能删
        assertThat(call(HttpMethod.PUT, "/api/rooms/" + roomId, guest,
                new UpdateRoomRequest("Hacked", null, null, null, null, null, null, null, null, null,
                        null, null, null, null), DETAIL).getBody().code()).isEqualTo(1302);
        assertThat(call(HttpMethod.DELETE, "/api/rooms/" + roomId, guest, null, VOID)
                .getBody().code()).isEqualTo(1302);

        // 不存在的房间
        assertThat(detail(owner, 999_999L).getBody().code()).isEqualTo(1301);
    }

    @Test
    void heartbeatTimeoutAndSoftDelete() {
        String owner = registerVerified("stale_owner");
        long roomId = create(owner, "Stale Room", 5).getBody().data().id();

        heartbeat(owner, roomId, 2);
        assertThat(detail(owner, roomId).getBody().data().online()).isTrue();

        // 用负超时把阈值推到未来，等价于「所有在线房间都已超时」
        assertThat(roomService.expireStale(Duration.ofSeconds(-1))).isPositive();
        assertThat(detail(owner, roomId).getBody().data().online()).isFalse();

        // 修改生效
        assertThat(call(HttpMethod.PUT, "/api/rooms/" + roomId, owner,
                new UpdateRoomRequest("Stale Room Renamed", null, null, null, null, 9, null, null, null, null,
                        null, null, null, null), DETAIL).getBody().data().name()).isEqualTo("Stale Room Renamed");

        // 软删除后详情不可见、大厅不再返回
        assertThat(call(HttpMethod.DELETE, "/api/rooms/" + roomId, owner, null, VOID)
                .getBody().code()).isZero();
        assertThat(detail(owner, roomId).getBody().code()).isEqualTo(1301);
        assertThat(lobbyIds(owner, "view=all&size=100")).doesNotContain(roomId);
    }

    @Test
    void roomContentMirrorAndClientPackageFlow() {
        String owner = registerVerified("content_owner");
        String guest = register("content_guest");
        long roomId = create(owner, "Content Room", 6).getBody().data().id();

        // 本地目录镜像初始为空；登录即可读
        assertThat(contents(guest, roomId).getBody().data()).isEmpty();

        // 非房主不能上报
        assertThat(call(HttpMethod.PUT, "/api/rooms/" + roomId + "/contents", guest,
                new ReportRoomContentRequest(List.of(
                        new ReportRoomContentRequest.Item("EssentialsX.jar", ContentType.PLUGIN, 1024, true))),
                CONTENTS).getBody().code()).isEqualTo(1302);

        // 房主整表替换上报
        var reported = call(HttpMethod.PUT, "/api/rooms/" + roomId + "/contents", owner,
                new ReportRoomContentRequest(List.of(
                        new ReportRoomContentRequest.Item("EssentialsX.jar", ContentType.PLUGIN, 1024, true),
                        new ReportRoomContentRequest.Item("jei.jar", ContentType.MOD, 2048, false))),
                CONTENTS);
        assertThat(reported.getBody().code()).isZero();
        assertThat(reported.getBody().data()).hasSize(2);
        assertThat(contents(guest, roomId).getBody().data()).hasSize(2);

        // 再次上报：本地删掉的文件在平台侧同步消失
        var replaced = call(HttpMethod.PUT, "/api/rooms/" + roomId + "/contents", owner,
                new ReportRoomContentRequest(List.of(
                        new ReportRoomContentRequest.Item("EssentialsX.jar", ContentType.PLUGIN, 1024, false))),
                CONTENTS);
        assertThat(replaced.getBody().data()).hasSize(1);
        assertThat(replaced.getBody().data().get(0).enabled()).isFalse();

        // 客户端包：初始无元数据
        assertThat(pkg(guest, roomId).getBody().data()).isNull();
        // 非 zip 被拒；超出 LV0 上限（5MB）被拒；非房主被拒
        assertThat(upload(owner, roomId, "client.rar", 128).getBody().code()).isEqualTo(1001);
        assertThat(upload(owner, roomId, "client.zip", 6 * 1024 * 1024).getBody().code()).isEqualTo(1401);
        assertThat(upload(guest, roomId, "client.zip", 128).getBody().code()).isEqualTo(1302);

        // 房主上传成功，玩家可下载（登录即可，不校验归属）
        var uploaded = upload(owner, roomId, "client.zip", 2048);
        assertThat(uploaded.getBody().code()).isZero();
        assertThat(uploaded.getBody().data().fileName()).isEqualTo("client.zip");
        var downloaded = rest.exchange("/api/rooms/" + roomId + "/client-package/file", HttpMethod.GET,
                new HttpEntity<>(bearer(guest)), byte[].class);
        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getBody()).hasSize(2048);

        // 重复上传即替换，房间只保留最新一份
        assertThat(upload(owner, roomId, "client-v2.zip", 4096).getBody().data().fileName())
                .isEqualTo("client-v2.zip");
        assertThat(pkg(owner, roomId).getBody().data().sizeBytes()).isEqualTo(4096);

        // 删除后元数据清空
        assertThat(call(HttpMethod.DELETE, "/api/rooms/" + roomId + "/client-package", owner, null, VOID)
                .getBody().code()).isZero();
        assertThat(pkg(owner, roomId).getBody().data()).isNull();

        // 重新上报两类，便于验证可见性开关对接口层的影响
        call(HttpMethod.PUT, "/api/rooms/" + roomId + "/contents", owner,
                new ReportRoomContentRequest(List.of(
                        new ReportRoomContentRequest.Item("EssentialsX.jar", ContentType.PLUGIN, 1024, true),
                        new ReportRoomContentRequest.Item("jei.jar", ContentType.MOD, 2048, true))),
                CONTENTS);

        // 清单可见性：默认对玩家可见
        assertThat(detail(guest, roomId).getBody().data().pluginListVisible()).isTrue();
        assertThat(detail(guest, roomId).getBody().data().modListVisible()).isTrue();

        // 房主关闭插件清单后，玩家侧详情同步为不可见；未触碰的 Mod 保持可见
        var hidden = call(HttpMethod.PUT, "/api/rooms/" + roomId, owner,
                new UpdateRoomRequest(null, null, null, null, null, null, null, null, null, null,
                        null, null, false, null), DETAIL);
        assertThat(hidden.getBody().code()).isZero();
        assertThat(hidden.getBody().data().pluginListVisible()).isFalse();
        assertThat(detail(guest, roomId).getBody().data().pluginListVisible()).isFalse();
        assertThat(detail(guest, roomId).getBody().data().modListVisible()).isTrue();

        // 接口层同步裁剪：非房主拿不到被隐藏的那一类明细，房主自己仍拿全量
        assertThat(contents(guest, roomId).getBody().data())
                .extracting(RoomContentDto::type)
                .containsExactly(ContentType.MOD);
        assertThat(contents(owner, roomId).getBody().data()).hasSize(2);

        // 重新打开即恢复可见
        assertThat(call(HttpMethod.PUT, "/api/rooms/" + roomId, owner,
                new UpdateRoomRequest(null, null, null, null, null, null, null, null, null, null,
                        null, null, true, null), DETAIL).getBody().data().pluginListVisible()).isTrue();
        assertThat(detail(guest, roomId).getBody().data().pluginListVisible()).isTrue();
        assertThat(contents(guest, roomId).getBody().data()).hasSize(2);
    }

    private ResponseEntity<ApiResponse<List<RoomContentDto>>> contents(String token, long roomId) {
        return call(HttpMethod.GET, "/api/rooms/" + roomId + "/contents", token, null, CONTENTS);
    }

    private ResponseEntity<ApiResponse<ClientPackageDto>> pkg(String token, long roomId) {
        return call(HttpMethod.GET, "/api/rooms/" + roomId + "/client-package", token, null, PKG);
    }

    private ResponseEntity<ApiResponse<ClientPackageDto>> upload(String token, long roomId,
                                                                 String fileName, int size) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(new byte[size]) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return rest.exchange("/api/rooms/" + roomId + "/client-package", HttpMethod.POST,
                new HttpEntity<>(form, headers), PKG);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register", null,
                new RegisterRequest(username, "password123"), TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().accessToken();
    }

    /** 注册并直接写入已验证的邮箱绑定，用于通过「创建房间需绑定邮箱」门槛 */
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

    private ResponseEntity<ApiResponse<RoomDetailDto>> create(String token, String name, int capacity) {
        var res = call(HttpMethod.POST, "/api/rooms", token,
                new CreateRoomRequest(name, "intro", "Paper", "1.21.1", "生存", capacity, false, false, false, null),
                DETAIL);
        assertThat(res.getBody().code()).isZero();
        return res;
    }

    private ResponseEntity<ApiResponse<RoomDetailDto>> detail(String token, long roomId) {
        return call(HttpMethod.GET, "/api/rooms/" + roomId, token, null, DETAIL);
    }

    private void heartbeat(String token, long roomId, int players) {
        assertThat(call(HttpMethod.POST, "/api/rooms/" + roomId + "/heartbeat", token,
                new HeartbeatRequest(players, 60, List.of()), VOID).getBody().code()).isZero();
    }

    private void putFlags(String token, long roomId, boolean locked, boolean noGuest, boolean needEmail) {
        assertThat(call(HttpMethod.PUT, "/api/rooms/" + roomId, token,
                new UpdateRoomRequest(null, null, null, null, null, null, locked, noGuest, needEmail, null,
                        null, null, null, null), DETAIL).getBody().code()).isZero();
    }

    private RoomSummaryDto findInLobby(String token, String query, long roomId) {
        var res = call(HttpMethod.GET, "/api/rooms?" + query, token, null, PAGE);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().items().stream()
                .filter(item -> item.id() == roomId)
                .findFirst()
                .orElse(null);
    }

    private List<Long> myRoomIds(String token) {
        var res = call(HttpMethod.GET, "/api/rooms/mine", token, null, MINE);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().stream().map(RoomSummaryDto::id).toList();
    }

    private List<Long> lobbyIds(String token, String query) {
        var res = call(HttpMethod.GET, "/api/rooms?" + query, token, null, PAGE);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().items().stream().map(RoomSummaryDto::id).toList();
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
}