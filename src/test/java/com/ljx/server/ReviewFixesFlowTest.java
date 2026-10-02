package com.ljx.server;

import com.ljx.server.admin.dto.AdminShopDtos.EffectExpiryRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码审查剩余项的回归：
 * ① **搜索关键字按字面匹配**（转义 like 通配符）且有长度上限；
 * ② 后台调整效果到期时间**有范围上限**（防止把效果"永久化"）；
 * ③ `/api/me` 展示的配额与**实际校验用的上限一致**（都走 QuotaService.effective）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewFixesFlowTest extends GlobalConfigGuardTest {

    private static final String ADMIN_NAME = "review_admin";
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
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<com.ljx.server.common.api.PageResult<com.ljx.server.room.dto.RoomDtos.RoomSummaryDto>>> PAGE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    /** 搜 "%" 应该按字面匹配（没有房间叫 %），而不是把全部房间都列出来 */
    @Test
    void searchKeywordIsTreatedLiterally() {
        String token = registerVerified("review_search");
        createRoom(token, "审查用房间A");
        createRoom(token, "审查用房间B");

        var all = search(token, "");
        assertThat(all.getBody().data().items()).as("未筛选时应能看到房间").isNotEmpty();

        var percent = search(token, "%");
        assertThat(percent.getBody().code()).isZero();
        assertThat(percent.getBody().data().items())
                .as("搜 %% 应按字面匹配：若不转义 like，这里会把所有房间都返回")
                .isEmpty();

        var underscore = search(token, "_");
        assertThat(underscore.getBody().data().items())
                .as("搜 _ 同样应按字面匹配").isEmpty();
    }

    /** 超长关键字不应让后端报错（会被截断） */
    @Test
    void overlongKeywordIsTruncatedNotRejected() {
        String token = registerVerified("review_long");
        var res = search(token, "x".repeat(500));
        assertThat(res.getBody().code()).as("超长关键字应被截断而不是报错").isZero();
    }

    /** 后台把效果到期时间设到 10 年以后应被拒绝（防止误传成"永久"） */
    @Test
    void effectExpiryHasAnUpperBound() {
        String admin = adminToken();
        long roomId = createRoom(registerVerified("review_expiry"), "到期校验房");

        // 房间还没有置顶记录时，超范围校验应先于"记录不存在"抛出
        var tooFar = call(HttpMethod.PUT, "/api/admin/shop/effects/top-cards/" + roomId,
                new EffectExpiryRequest(LocalDateTime.now().plusYears(20)), admin, VOID);
        assertThat(tooFar.getBody().code())
                .as("20 年后的到期时间应被拒绝").isEqualTo(1001);

        // 正常范围内（例如 1 天后）不应因范围校验被拒（这里因为没记录会报 1301，但不是 1001）
        var ok = call(HttpMethod.PUT, "/api/admin/shop/effects/top-cards/" + roomId,
                new EffectExpiryRequest(LocalDateTime.now().plusDays(1)), admin, VOID);
        assertThat(ok.getBody().code()).as("范围内的时间不应被范围校验拦下").isNotEqualTo(1001);
    }

    /** /api/me 展示的配额必须等于实际校验用的上限（都来自配置，而不是旧的物化值） */
    @Test
    void reportedQuotaMatchesConfiguredLimit() {
        String username = "review_quota";
        String token = register(username);
        Long accountId = jdbcTemplate.queryForObject(
                "select id from account where username = ?", Long.class, username);

        Integer configured = jdbcTemplate.queryForObject(
                "select upload_mb from level_config where level = 0", Integer.class);
        Map<String, Integer> reported = quotas(token);
        assertThat(reported.get("CLIENT_PKG_MB"))
                .as("/api/me 报的上传上限应等于 level_config 里配置的值（而不是旧的物化值）")
                .isEqualTo(configured);

        // 把配置改掉后，展示值应立刻跟着变（说明它不是读死的物化值）
        jdbcTemplate.update("update level_config set upload_mb = 77 where level = 0");
        assertThat(quotas(token).get("CLIENT_PKG_MB"))
                .as("改了后台配置，展示值应立刻跟随").isEqualTo(77);
    }

    // ---------- 辅助 ----------

    private Map<String, Integer> quotas(String token) {
        var res = call(HttpMethod.GET, "/api/me", null, token, MAP);
        assertThat(res.getBody().code()).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) res.getBody().data().get("quotas");
        Map<String, Integer> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(key, ((Number) value).intValue()));
        return result;
    }

    private ResponseEntity<ApiResponse<com.ljx.server.common.api.PageResult<com.ljx.server.room.dto.RoomDtos.RoomSummaryDto>>> search(
            String token, String keyword) {
        String path = "/api/rooms?view=all&q=" + java.net.URLEncoder.encode(keyword,
                java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.GET, path, null, token, PAGE);
    }

    private long createRoom(String token, String name) {
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
        var res = call(HttpMethod.POST, "/api/rooms", body, token,
                new ParameterizedTypeReference<ApiResponse<com.ljx.server.room.dto.RoomDtos.RoomDetailDto>>() {
                });
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().id();
    }

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    /** 建房要求邮箱已验证；测试里直接写一条绑定，避免走邮件取码流程 */
    private String registerVerified(String username) {
        String token = register(username);
        Long accountId = jdbcTemplate.queryForObject(
                "select id from account where username = ?", Long.class, username);
        jdbcTemplate.update("delete from email_binding where account_id = ?", accountId);
        jdbcTemplate.update("insert into email_binding (account_id, email, verified) values (?, ?, 1)",
                accountId, "t" + accountId + "@example.com");
        return token;
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
