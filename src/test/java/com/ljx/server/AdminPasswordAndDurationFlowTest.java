package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.admin.dto.AdminDtos.ChangePasswordRequest;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本轮两项需求的回归：
 * ① 管理员**登录后可改密码**（防止别人用初始/默认密码进后台）；
 * ② 置顶卡与 VIP 的**时长由后台配置**，并且正确影响购买后的到期时间。
 * <p>
 * 用**独立的管理员账号**，避免改密码把其他用例共用的账号改掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminPasswordAndDurationFlowTest extends GlobalConfigGuardTest {

    private static final String ADMIN_NAME = "pwd_dur_admin";
    private static final String ADMIN_PASSWORD = "secret12345";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> PLAYER_TOKEN =
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
        // 每个用例都把密码重置成初始值，用例之间互不影响
        adminUserRepository.findByUsername(ADMIN_NAME).ifPresent(adminUserRepository::delete);
        adminUserRepository.save(new AdminUser(
                ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
    }

    @Test
    void adminCanChangeOwnPassword() {
        String token = adminToken(ADMIN_PASSWORD);
        String newPassword = "brand-new-pass-2026";

        // 旧密码不对 → 1102
        assertThat(changePassword(token, "wrong-old", newPassword).getBody().code()).isEqualTo(1102);
        // 新密码太短 → 1001（DTO 校验）
        assertThat(changePassword(token, ADMIN_PASSWORD, "short").getBody().code()).isEqualTo(1001);
        // 新旧相同 → 1001
        assertThat(changePassword(token, ADMIN_PASSWORD, ADMIN_PASSWORD).getBody().code()).isEqualTo(1001);

        // 正常修改
        assertThat(changePassword(token, ADMIN_PASSWORD, newPassword).getBody().code()).isZero();

        // 旧密码不能再登录，新密码可以
        assertThat(loginCode(ADMIN_PASSWORD)).isEqualTo(1102);
        assertThat(loginCode(newPassword)).isZero();
    }

    /**
     * 强制改密码：首次创建 / 被重置的账号，改密码之前**除本接口外的管理调用一律拒绝**。
     * 这是防止"默认密码登录后台就能直接操作"的最后一道闸门。
     */
    @Test
    void forcedPasswordChangeBlocksOtherAdminApis() {
        // 模拟"由环境变量首次创建"的账号：构造器不带第四个参数时默认就要求强制改密码
        adminUserRepository.findByUsername(ADMIN_NAME).ifPresent(adminUserRepository::delete);
        adminUserRepository.save(new AdminUser(
                ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now()));

        // 登录本身必须放行，并在响应里告诉前端"要先改密码"
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null,
                new ParameterizedTypeReference<ApiResponse<AdminLoginResponse>>() {
                });
        assertThat(login.getBody().code()).isZero();
        assertThat(login.getBody().data().mustChangePassword())
                .as("新建账号应带「必须先改密码」标记").isTrue();
        String token = login.getBody().data().token();

        // /me 也能读到这个标记（前端据此弹强制窗口）
        var me = call(HttpMethod.GET, "/api/admin/me", null, token, MAP);
        assertThat(me.getBody().data().get("mustChangePassword")).isEqualTo(true);

        // 其它管理接口全部被拒：1604 = 请先修改管理员密码
        assertThat(call(HttpMethod.GET, "/api/admin/mail", null, token, MAP)
                .getBody().code()).as("未改密码前不允许读邮件配置").isEqualTo(1604);
        assertThat(call(HttpMethod.GET, "/api/admin/levels", null, token, MAP)
                .getBody().code()).isEqualTo(1604);
        assertThat(call(HttpMethod.GET, "/api/admin/storage", null, token, MAP)
                .getBody().code()).isEqualTo(1604);

        // 但"修改密码"本身必须放行，否则管理员会被永久锁在门外
        String newPassword = "must-change-2026";
        assertThat(changePassword(token, ADMIN_PASSWORD, newPassword).getBody().code())
                .as("强制状态下必须允许修改密码").isZero();

        // 改完之后一切恢复正常
        assertThat(call(HttpMethod.GET, "/api/admin/mail", null, token, MAP)
                .getBody().code()).as("改完密码后应能正常访问").isZero();
        assertThat(call(HttpMethod.GET, "/api/admin/me", null, token, MAP)
                .getBody().data().get("mustChangePassword")).isEqualTo(false);
        // 新密码可用于再次登录
        assertThat(loginCode(newPassword)).isZero();
    }

    @Test
    void changePasswordRequiresToken() {
        assertThat(call(HttpMethod.PUT, "/api/admin/password",
                new ChangePasswordRequest(ADMIN_PASSWORD, "another-pass-123"), null, VOID)
                .getBody().code()).isEqualTo(1104);
    }

    /** 置顶卡时长改成 3 天，买下后到期应正好是「今天 + 3 天」 */
    @Test
    void topCardDurationComesFromAdminConfig() {
        String admin = adminToken(ADMIN_PASSWORD);
        Map<String, Object> item = firstItem(admin, "TOP_CARD");
        long itemId = ((Number) item.get("id")).longValue();

        assertThat(call(HttpMethod.PUT, "/api/admin/shop/items/" + itemId,
                Map.of("durationDays", 3), admin, MAP).getBody().code()).isZero();

        String token = registerAndVerifyEmail("dur_top");
        long roomId = createRoom(token, "时长测试房");
        refillByCdk(token, admin, 5000);

        var buy = call(HttpMethod.POST, "/api/commerce/purchase",
                Map.of("itemId", itemId, "roomId", roomId), token, MAP);
        assertThat(buy.getBody().code()).isZero();
        String expiresAt = String.valueOf(buy.getBody().data().get("expiresAt"));
        assertThat(expiresAt).as("到期时间应来自后台配置的 3 天").startsWith(
                LocalDate.now().plusDays(3).toString());
    }

    /** VIP 时长改成 5 天，买下后 vipExpiresAt 应是「今天 + 5 天」，且 /api/me 能看到 */
    @Test
    void vipDurationComesFromAdminConfigAndIsVisible() {
        String admin = adminToken(ADMIN_PASSWORD);
        assertThat(call(HttpMethod.PUT, "/api/admin/shop/vip-plans/1",
                Map.of("durationDays", 5), admin, MAP).getBody().code()).isZero();

        String token = registerAndVerifyEmail("dur_vip");
        refillByCdk(token, admin, 5000);
        assertThat(call(HttpMethod.POST, "/api/commerce/vip/purchase", Map.of("level", 1), token, MAP)
                .getBody().code()).isZero();

        Map<String, Object> me = call(HttpMethod.GET, "/api/me", null, token, MAP).getBody().data();
        assertThat(String.valueOf(me.get("vipExpiresAt")))
                .as("买完要能看到到期时间（原先 AccountDto 根本没这个字段）")
                .startsWith(LocalDate.now().plusDays(5).toString());

        // 商城接口也要能读到档位时长，前端才能显示"有效期 N 天"
        var vip = call(HttpMethod.GET, "/api/commerce/vip", null, token,
                new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                }).getBody().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) vip.get("plans");
        assertThat(plans.stream().filter(p -> ((Number) p.get("level")).intValue() == 1).findFirst()
                .orElseThrow().get("durationDays")).isEqualTo(5);
    }

    /** 时长留空（存成 0）应被解释为「永久」，而不是「0 天立刻过期」 */
    @Test
    void zeroDurationMeansForever() {
        String admin = adminToken(ADMIN_PASSWORD);
        Map<String, Object> item = firstItem(admin, "TOP_CARD");
        long itemId = ((Number) item.get("id")).longValue();
        assertThat(call(HttpMethod.PUT, "/api/admin/shop/items/" + itemId,
                Map.of("durationDays", 0), admin, MAP).getBody().code()).isZero();

        String token = registerAndVerifyEmail("dur_forever");
        long roomId = createRoom(token, "永久测试房");
        refillByCdk(token, admin, 5000);

        var buy = call(HttpMethod.POST, "/api/commerce/purchase",
                Map.of("itemId", itemId, "roomId", roomId), token, MAP);
        assertThat(buy.getBody().code()).isZero();
        // 永久不能用 null 表示：room_top_card.expires_at 是 NOT NULL，
        // 且 account.vip_expires_at 用 null 表示"从未购买"（会让永久 VIP 被判成过期）。
        // 所以永久 = 一个远期时间；这里断言它远在百年之后。
        String expires = String.valueOf(buy.getBody().data().get("expiresAt"));
        assertThat(expires).as("0 应表示永久，而不是 0 天后过期").startsWith("9999-");
    }

    // ---------- 辅助 ----------

    private ResponseEntity<ApiResponse<Void>> changePassword(String token, String oldPwd, String newPwd) {
        return call(HttpMethod.PUT, "/api/admin/password",
                new ChangePasswordRequest(oldPwd, newPwd), token, VOID);
    }

    private int loginCode(String password) {
        var res = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, password), null, ADMIN_TOKEN);
        return res.getBody().code();
    }

    private String adminToken(String password) {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, password), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    private Map<String, Object> firstItem(String admin, String effectKind) {
        var res = call(HttpMethod.GET, "/api/admin/shop/items", null, admin,
                new ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>>() {
                });
        return res.getBody().data().stream()
                .filter(item -> effectKind.equals(item.get("effectKind")))
                .findFirst().orElseThrow();
    }

    private String registerAndVerifyEmail(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest(username, "password123"), null, PLAYER_TOKEN);
        assertThat(res.getBody().code()).isZero();
        String token = res.getBody().data().accessToken();
        Long accountId = jdbcTemplate.queryForObject(
                "select id from account where username = ?", Long.class, username);
        jdbcTemplate.update("delete from email_binding where account_id = ?", accountId);
        jdbcTemplate.update("insert into email_binding (account_id, email, verified) values (?, ?, 1)",
                accountId, "t" + accountId + "@example.com");
        return token;
    }

    private void refillByCdk(String playerToken, String adminToken, int coins) {
        var batch = call(HttpMethod.POST, "/api/admin/cdk/batches",
                Map.of("count", 1, "coins", coins, "totalUses", 1, "validDays", 1), adminToken, MAP);
        assertThat(batch.getBody().code()).isZero();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) batch.getBody().data().get("items");
        assertThat(call(HttpMethod.POST, "/api/commerce/redeem",
                Map.of("code", items.get(0).get("code")), playerToken, MAP).getBody().code()).isZero();
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
        var res = call(HttpMethod.POST, "/api/rooms", body, token, MAP);
        assertThat(res.getBody().code()).as("建房应成功（邮箱已验证）").isZero();
        return ((Number) res.getBody().data().get("id")).longValue();
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
