package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementRequest;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.ws.LobbyEvent;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两件事：
 * ① **公告是实时的** —— 后台改完会发一个 {@code ANNOUNCEMENT} 广播事件（走大厅推送通道），
 *    在线客户端立即重拉，不必等 5 分钟轮询；
 * ② **存储概况可读** —— 尤其是"是否落在系统临时目录"，那是"客户端包会不会丢"的关键信号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorageAndRealtimeFlowTest {

    private static final String ADMIN_NAME = "storage_admin";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    /**
     * 自己注册一个监听器收集 LobbyEvent。
     * 不用 @RecordApplicationEvents：它记录事件的时机与事务/监听阶段相关，实测拿不到；
     * 自注册监听器是"发布即收到"，最可靠。
     */
    @Autowired
    private org.springframework.context.ApplicationContext context;

    private static final java.util.List<LobbyEvent> SEEN = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean hooked = false;

    private void hookEvents() {
        SEEN.clear();
        if (!hooked) {
            ((org.springframework.context.support.AbstractApplicationContext) context)
                    .addApplicationListener(event -> {
                        // 注意：LobbyEvent 是 record、不是 ApplicationEvent，
                        // Spring 会用 PayloadApplicationEvent 把它包一层再派发，
                        // 所以这里要先取 payload（@EventListener 方法参数那套是框架帮我们解包的）。
                        Object payload = event instanceof org.springframework.context.PayloadApplicationEvent<?> wrapped
                                ? wrapped.getPayload()
                                : event;
                        if (payload instanceof LobbyEvent lobbyEvent) {
                            SEEN.add(lobbyEvent);
                        }
                    });
            hooked = true;
        }
    }

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        hookEvents();
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    @Test
    void changingAnnouncementPublishesRealtimeEvent() {
        String admin = adminToken();

        var created = call(HttpMethod.POST, "/api/admin/announcements",
                new AnnouncementRequest("实时推送测试公告", 999, true), admin, MAP);
        assertThat(created.getBody().code()).isZero();
        long id = ((Number) created.getBody().data().get("id")).longValue();

        // 新增应触发 ANNOUNCEMENT 广播（事务提交后发出）
        assertThat(SEEN)
                .as("公告新增后应广播 ANNOUNCEMENT 事件，客户端据此立刻重拉")
                .anySatisfy(event -> assertThat(event.type()).isEqualTo(LobbyEvent.Type.ANNOUNCEMENT));

        // 删除也应触发
        long before = SEEN.stream()
                .filter(event -> event.type() == LobbyEvent.Type.ANNOUNCEMENT).count();
        assertThat(call(HttpMethod.DELETE, "/api/admin/announcements/" + id, null, admin, MAP)
                .getBody().code()).isZero();
        assertThat(SEEN.stream()
                .filter(event -> event.type() == LobbyEvent.Type.ANNOUNCEMENT).count())
                .as("公告删除后也应广播").isGreaterThan(before);
    }

    @Test
    void storageInfoReportsDirectoryAndTempFlag() {
        var res = call(HttpMethod.GET, "/api/admin/storage", null, adminToken(), MAP);
        assertThat(res.getBody().code()).isZero();
        Map<String, Object> info = res.getBody().data();

        assertThat(info.get("dir")).as("要能看到客户端包存哪").isNotNull();
        // tempDir 是"会不会丢包"的关键信号：生产环境应为 false
        assertThat(info).containsKey("tempDir");
        assertThat(((Number) info.get("packageFiles")).intValue()).isNotNegative();
        assertThat(((Number) info.get("roomCount")).intValue()).isNotNegative();
        assertThat(((Number) info.get("orphanFiles")).intValue()).isNotNegative();
    }

    @Test
    void storageApiRequiresAdminToken() {
        assertThat(call(HttpMethod.GET, "/api/admin/storage", null, null, MAP)
                .getBody().code()).isEqualTo(1104);
    }

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    private <T> ResponseEntity<ApiResponse<T>> call(HttpMethod method, String path, Object body,
                                                    String token,
                                                    ParameterizedTypeReference<ApiResponse<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}
