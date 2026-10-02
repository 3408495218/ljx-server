package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.common.api.ApiResponse;
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
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 修复的回归：
 * ① 业务写接口现在**有限流**（原先只有登录类接口有，建房/购买/兑换可被刷）；
 * ② SMTP 配置可在后台读写，且**密码永不回显**。
 * <p>
 * 限流阈值通过 {@code @TestPropertySource} 调到 3 次，避免测试里真发几十个请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 测试环境默认关闭限流（src/test/resources/application.properties），本用例要验证限流必须显式打开；
        // 同时放宽其它规则，避免注册等前置请求把窗口用满
        "ljx.rate-limit.enabled=true",
        "ljx.rate-limit.room-write.limit=3",
        "ljx.rate-limit.room-write.window=10m",
        "ljx.rate-limit.register.limit=50",
        "ljx.rate-limit.email-code.limit=50",
})
class RateLimitAndMailFlowTest {

    private static final String ADMIN_NAME = "p1_admin";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> PLAYER_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private com.ljx.server.common.ratelimit.RateLimitProperties rateLimitProperties;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    /** 建房这类写接口现在会被限流；阈值设为 3，第 4 次应拿到 429 */
    @Test
    void roomWriteIsRateLimited() {
        // 先确认测试用的阈值真的被覆盖了（否则 6 次请求撞不到默认的 60 次，用例会假失败）
        assertThat(rateLimitProperties.getRoomWrite().limit())
                .as("测试应把建房限流阈值覆盖为 3").isEqualTo(3);

        String token = register("p1_ratelimit");
        int denied = 0;
        for (int i = 0; i < 6; i++) {
            int status = createRoomStatus(token, "限流房" + i);
            if (status == 429) {
                denied++;
            }
        }
        assertThat(denied)
                .as("超过阈值后建房请求应被限流（原先建房完全不限流）")
                .isPositive();
    }

    /** 读接口不受影响：列表查询不该被写接口的限流规则拦下 */
    @Test
    void readEndpointsAreNotRateLimited() {
        String token = register("p1_read");
        for (int i = 0; i < 10; i++) {
            var res = call(HttpMethod.GET, "/api/rooms?view=all", null, token, MAP);
            assertThat(res.getStatusCode().value()).as("读接口不应被限流").isNotEqualTo(429);
        }
    }

    @Test
    void mailConfigIsReadableAndPasswordNeverEchoed() {
        String admin = adminToken();

        var res = call(HttpMethod.GET, "/api/admin/mail", null, admin, MAP);
        assertThat(res.getBody().code()).isZero();
        Map<String, Object> cfg = res.getBody().data();
        assertThat(cfg).containsKeys("host", "port", "username", "passwordSet", "source", "configured");
        assertThat(cfg).as("响应里绝不能出现密码字段本身").doesNotContainKey("password");

        // 保存一份配置后应能读回（密码只回"是否已设置"）
        var saved = call(HttpMethod.PUT, "/api/admin/mail", Map.of(
                "enabled", true,
                "host", "smtp.example.com",
                "port", 465,
                "username", "user@example.com",
                "password", "super-secret",
                "from", "user@example.com",
                "sslEnabled", true), admin, MAP);
        assertThat(saved.getBody().code()).isZero();
        Map<String, Object> after = saved.getBody().data();
        assertThat(after.get("host")).isEqualTo("smtp.example.com");
        assertThat(after.get("passwordSet")).as("密码只回是否已设置").isEqualTo(true);
        assertThat(after.toString()).as("任何地方都不能回显密码").doesNotContain("super-secret");
        assertThat(after.get("configured")).as("配置齐全后应判定为可用").isEqualTo(true);
        assertThat(after.get("source")).isEqualTo("后台配置");

        // 还原：关掉，避免影响其它用例（尤其是不希望真有 SMTP 通道时）
        call(HttpMethod.PUT, "/api/admin/mail", Map.of("enabled", false), admin, MAP);
    }

    @Test
    void mailApiRequiresAdminToken() {
        assertThat(call(HttpMethod.GET, "/api/admin/mail", null, null, MAP)
                .getBody().code()).isEqualTo(1104);
    }

    // ---------- 辅助 ----------

    private int createRoomStatus(String token, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("intro", "x");
        body.put("core", "Paper-1.20.4");
        body.put("mcVersion", "1.20.4");
        body.put("mode", "生存");
        body.put("capacity", 10);
        body.put("locked", false);
        body.put("noGuest", false);
        body.put("needEmail", false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange("/api/rooms", HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
                .getStatusCode().value();
    }

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    private String register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest(username, "password123"), null, PLAYER_TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().accessToken();
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
