package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.cdk.CdkService;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchRequest;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchResponse;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchSummaryDto;
import com.ljx.server.cdk.dto.CdkDtos.CdkDto;
import com.ljx.server.cdk.dto.CdkDtos.CdkToggleRequest;
import com.ljx.server.cdk.dto.CdkDtos.RedeemRequest;
import com.ljx.server.cdk.dto.CdkDtos.RedeemResponse;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CDK：批量生成、兑换加金币、重复/停用/用完/不存在的失败路径，以及**并发只成功一次**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CdkFlowTest {

    private static final String ADMIN_NAME = "cdk_tester";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<CdkBatchResponse>> BATCH =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<CdkDto>> CDK_ONE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<RedeemResponse>> REDEEM =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> PLAYER_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private CdkService cdkService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    @Test
    void redeemAddsCoinsAndIsOncePerAccount() {
        String admin = adminToken();
        String player = playerToken("cdk_player_1");
        CdkBatchResponse batch = createBatch(admin, 3, 100, 0, null);

        String code = batch.items().get(0).code();
        var first = redeem(code, player);
        assertThat(first.getBody().code()).isZero();
        assertThat(first.getBody().data().gained()).isEqualTo(100);
        assertThat(first.getBody().data().coins()).isEqualTo(100);

        // 同一账号再兑同一个码 → 1506（不限次的码也不允许同人反复领）
        assertThat(redeem(code, player).getBody().code()).isEqualTo(1506);
    }

    /** 同一秒内连续生成两批，批次号必须不同（否则两批会被汇总成一批） */
    @Test
    void twoBatchesInSameSecondGetDistinctBatchNo() {
        String admin = adminToken();
        CdkBatchResponse first = createBatch(admin, 2, 10, 1, null);
        CdkBatchResponse second = createBatch(admin, 3, 20, 1, null);
        assertThat(first.batchNo()).isNotEqualTo(second.batchNo());

        List<CdkBatchSummaryDto> summaries = call(HttpMethod.GET, "/api/admin/cdk/batches", null, admin,
                new ParameterizedTypeReference<ApiResponse<List<CdkBatchSummaryDto>>>() {
                }).getBody().data();
        // 两批各自独立成行
        assertThat(summaries).extracting(CdkBatchSummaryDto::batchNo)
                .contains(first.batchNo(), second.batchNo());
        CdkBatchSummaryDto firstRow = summaries.stream()
                .filter(row -> row.batchNo().equals(first.batchNo())).findFirst().orElseThrow();
        CdkBatchSummaryDto secondRow = summaries.stream()
                .filter(row -> row.batchNo().equals(second.batchNo())).findFirst().orElseThrow();
        assertThat(firstRow.total()).isEqualTo(2);
        assertThat(firstRow.coins()).isEqualTo(10);
        assertThat(secondRow.total()).isEqualTo(3);
        assertThat(secondRow.coins()).isEqualTo(20);
    }

    /** 单次码：同一个人再兑，应提示"已领取过"(1506) 而不是"码被领完"(1505) */
    @Test
    void singleUseCodeRepeatedBySameAccountReportsAlreadyRedeemed() {
        String admin = adminToken();
        String player = playerToken("cdk_once_player");
        String code = createBatch(admin, 1, 10, 1, null).items().get(0).code();

        assertThat(redeem(code, player).getBody().code()).isZero();
        assertThat(redeem(code, player).getBody().code()).isEqualTo(1506);
        // 换个人来兑才是"已被领完"
        assertThat(redeem(code, playerToken("cdk_once_other")).getBody().code()).isEqualTo(1505);
    }

    @Test
    void unlimitedCodeAcceptsManyAccounts() {
        String admin = adminToken();
        CdkBatchResponse batch = createBatch(admin, 1, 50, 0, null);
        String code = batch.items().get(0).code();

        assertThat(redeem(code, playerToken("cdk_multi_a")).getBody().code()).isZero();
        assertThat(redeem(code, playerToken("cdk_multi_b")).getBody().code()).isZero();
    }

    /** 关键：单次码被 10 个账号并发兑换，只能成功一个（行锁 + 名额校验） */
    @Test
    void concurrentRedeemOnlySucceedsOnce() throws Exception {
        String admin = adminToken();
        String code = createBatch(admin, 1, 30, 1, null).items().get(0).code();

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(registerAccount("cdk_conc_" + i));
        }

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Long accountId = ids.get(i);
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        cdkService.redeem(accountId, code);
                        return "OK";
                    } catch (Exception e) {
                        return e.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<String> result : results) {
                if ("OK".equals(result.get())) {
                    ok++;
                }
            }
            assertThat(ok).as("同一单次码并发兑换只能成功一次").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unknownDisabledAndUsedUpCodesAreRejected() {
        String admin = adminToken();
        String player = playerToken("cdk_fail_player");

        // 不存在 → 1502
        assertThat(redeem("NOSUCHCODE123456", player).getBody().code()).isEqualTo(1502);

        // 停用 → 1503
        CdkDto disabled = createBatch(admin, 1, 10, 0, null).items().get(0);
        assertThat(call(HttpMethod.PUT, "/api/admin/cdk/" + disabled.id(),
                new CdkToggleRequest(false), admin, CDK_ONE).getBody().code()).isZero();
        assertThat(redeem(disabled.code(), player).getBody().code()).isEqualTo(1503);

        // 已被别人领完 → 1505
        String onceCode = createBatch(admin, 1, 10, 1, null).items().get(0).code();
        assertThat(redeem(onceCode, player).getBody().code()).isZero();
        assertThat(redeem(onceCode, playerToken("cdk_fail_other")).getBody().code()).isEqualTo(1505);
    }

    @Test
    void redeemedCodeCannotBeDeletedOnlyDisabled() {
        String admin = adminToken();
        CdkDto dto = createBatch(admin, 1, 10, 0, null).items().get(0);
        assertThat(redeem(dto.code(), playerToken("cdk_del_player")).getBody().code()).isZero();

        assertThat(call(HttpMethod.DELETE, "/api/admin/cdk/" + dto.id(), null, admin, VOID)
                .getBody().code()).isEqualTo(1508);
        assertThat(call(HttpMethod.PUT, "/api/admin/cdk/" + dto.id(),
                new CdkToggleRequest(false), admin, CDK_ONE).getBody().code()).isZero();
    }

    // ---------- 辅助 ----------

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    private String playerToken(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest(username, "password123"), null, PLAYER_TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().accessToken();
    }

    private Long registerAccount(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest(username, "password123"), null, PLAYER_TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().account().id();
    }

    private CdkBatchResponse createBatch(String admin, int count, int coins, int totalUses,
                                        Integer validDays) {
        var res = call(HttpMethod.POST, "/api/admin/cdk/batches",
                new CdkBatchRequest(count, coins, totalUses, validDays), admin, BATCH);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private ResponseEntity<ApiResponse<RedeemResponse>> redeem(String code, String playerToken) {
        return call(HttpMethod.POST, "/api/commerce/redeem", new RedeemRequest(code), playerToken, REDEEM);
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
