package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.auth.dto.AuthDtos.DailyRewardDto;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.quota.QuotaService;
import com.ljx.server.quota.QuotaType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 普通玩家等级机制：**每天登录一次获得一次经验**；升级所需经验与各等级上传上限由后台配置。
 * <p>
 * 注意：`AuthFlowTest` 里也有注册/登录用例，这里用独立用户名，避免共用 H2 库时互相干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LevelFlowTest extends GlobalConfigGuardTest {

    private static final String ADMIN_NAME = "level_admin";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> LOGIN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> OVERVIEW =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Integer>> ONE_INT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private QuotaService quotaService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    @Test
    void dailyLoginGrantsExpOnlyOncePerDay() {
        register("lvl_once");
        // 第一次登录：应发经验
        DailyRewardDto first = login("lvl_once").dailyReward();
        assertThat(first).as("登录响应要带上每日奖励，客户端才能提示").isNotNull();
        assertThat(first.granted()).as("当天首次登录应发经验").isTrue();
        assertThat(first.expGained()).isPositive();

        // 同一天再登录：不再发
        DailyRewardDto second = login("lvl_once").dailyReward();
        assertThat(second.granted()).as("同一天重复登录不再发经验").isFalse();
        assertThat(second.expGained()).isZero();
    }

    /** 攒够经验就升级，升级后按新等级刷新上传上限（阈值与上限都来自后台配置） */
    @Test
    void expAccumulatesAndLevelsUpWithConfiguredQuota() {
        String admin = adminToken();
        // 把每日经验临时调大，一次登录就能升级（0 级需要 100 经验）
        assertThat(call(HttpMethod.PUT, "/api/admin/levels/daily-exp",
                Map.of("exp", 250), admin, ONE_INT).getBody().code()).isZero();

        try {
            register("lvl_up");
            long accountId = accountRepository.findByUsernameAndDeletedAtIsNull("lvl_up").orElseThrow().getId();
            int base = quotaService.effective(accountId, QuotaType.CLIENT_PKG_MB);
            assertThat(base).as("0 级的上传上限来自 level_config（5MB）").isEqualTo(5);

            DailyRewardDto reward = login("lvl_up").dailyReward();
            assertThat(reward.granted()).isTrue();
            assertThat(reward.levelsUp()).as("250 经验应升到 1 级（0 级需 100）").isEqualTo(1);
            assertThat(reward.level()).isEqualTo(1);
            assertThat(reward.score()).as("升级时扣掉该级所需经验，score 是级内进度").isEqualTo(150);
            assertThat(reward.expToNext()).as("1 级升级需要 200 经验").isEqualTo(200);

            Account account = accountRepository.findById(accountId).orElseThrow();
            assertThat(account.getLevel()).isEqualTo(1);
            assertThat(account.getLastDailyRewardOn()).isEqualTo(LocalDate.now());
            assertThat(quotaService.effective(accountId, QuotaType.CLIENT_PKG_MB))
                    .as("升级后上传上限应变为 1 级的 10MB").isEqualTo(10);
        } finally {
            // 复原配置，避免影响其他用例
            call(HttpMethod.PUT, "/api/admin/levels/daily-exp", Map.of("exp", 1), admin, ONE_INT);
        }
    }

    @Test
    void adminCanReadAndChangeLevelConfig() {
        String admin = adminToken();
        var overview = call(HttpMethod.GET, "/api/admin/levels", null, admin, OVERVIEW);
        assertThat(overview.getBody().code()).isZero();
        assertThat(overview.getBody().data().get("dailyLoginExp")).isNotNull();
        assertThat((java.util.List<?>) overview.getBody().data().get("levels"))
                .as("等级配置应有多档").isNotEmpty();

        // 改 1 级的上传上限 → 配额校验立刻用新值
        var updated = call(HttpMethod.PUT, "/api/admin/levels/1",
                Map.of("uploadMb", 66), admin,
                new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
        assertThat(updated.getBody().code()).isZero();
        assertThat(updated.getBody().data().get("uploadMb")).isEqualTo(66);

        // 复原
        call(HttpMethod.PUT, "/api/admin/levels/1", Map.of("uploadMb", 10), admin,
                new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                });
    }

    @Test
    void unknownLevelConfigIsRejected() {
        assertThat(call(HttpMethod.PUT, "/api/admin/levels/99",
                Map.of("uploadMb", 1), adminToken(), ONE_INT).getBody().code()).isEqualTo(1515);
    }

    @Test
    void levelApiRequiresAdminToken() {
        assertThat(call(HttpMethod.GET, "/api/admin/levels", null, null, OVERVIEW)
                .getBody().code()).isEqualTo(1104);
    }

    // ---------- 辅助 ----------

    private void register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new com.ljx.server.auth.dto.AuthDtos.RegisterRequest(username, "password123"), null, LOGIN);
        assertThat(res.getBody().code()).isZero();
    }

    private TokenResponse login(String username) {
        var res = call(HttpMethod.POST, "/api/auth/login",
                new com.ljx.server.auth.dto.AuthDtos.LoginRequest(username, "password123"), null, LOGIN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
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
