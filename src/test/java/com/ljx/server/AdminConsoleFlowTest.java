package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.admin.dto.AdminDtos.AdminMeResponse;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理后台骨架：登录、令牌校验、以及与玩家认证的**隔离**。
 * <p>
 * 注意测试启动时 {@code AdminBootstrap} 会因为测试配置里没有 LJX_ADMIN_* 而不创建管理员，
 * 所以这里手工插一个，避免用例依赖部署环境变量。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminConsoleFlowTest {

    private static final String ADMIN_NAME = "tester";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AdminMeResponse>> ADMIN_ME =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> PLAYER_TOKEN =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    /** 与主代码一致：项目里没有 PasswordEncoder Bean，直接持有实例 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, passwordEncoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    @Test
    void loginThenAccessMe() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        AdminLoginResponse data = login.getBody().data();
        assertThat(data.username()).isEqualTo(ADMIN_NAME);
        assertThat(data.expiresInSeconds()).isPositive();

        var me = call(HttpMethod.GET, "/api/admin/me", null, data.token(), ADMIN_ME);
        assertThat(me.getBody().code()).isZero();
        assertThat(me.getBody().data().username()).isEqualTo(ADMIN_NAME);
    }

    @Test
    void wrongPasswordIsRejected() {
        var res = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, "wrong-password"), null, ADMIN_TOKEN);
        assertThat(res.getBody().code()).isEqualTo(1102);
    }

    @Test
    void unknownAdminGetsSameErrorAsWrongPassword() {
        // 不区分"账号不存在"与"密码错误"，避免枚举账号
        var res = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest("no-such-admin", "whatever"), null, ADMIN_TOKEN);
        assertThat(res.getBody().code()).isEqualTo(1102);
    }

    @Test
    void adminEndpointRequiresToken() {
        var res = call(HttpMethod.GET, "/api/admin/me", null, null, ADMIN_ME);
        assertThat(res.getBody().code()).isEqualTo(1104);
    }

    /**
     * 关键隔离：玩家令牌（typ=access）**不能**访问管理接口，
     * 且管理令牌也不能拿去访问玩家接口。
     */
    @Test
    void playerTokenCannotAccessAdminApi() {
        var reg = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest("admin_iso_player", "password123"), null, PLAYER_TOKEN);
        assertThat(reg.getBody().code()).isZero();
        String playerToken = reg.getBody().data().accessToken();

        // 玩家令牌打管理接口 → 按"管理端令牌无效"处理
        assertThat(call(HttpMethod.GET, "/api/admin/me", null, playerToken, ADMIN_ME)
                .getBody().code()).isEqualTo(1103);

        // 管理令牌打玩家接口 → 玩家认证拦截器同样拒绝
        var adminLogin = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        String adminToken = adminLogin.getBody().data().token();
        assertThat(call(HttpMethod.GET, "/api/me", null, adminToken, VOID).getBody().code())
                .isIn(1103, 1104);
    }

    @Test
    void logoutIsAuditedAndIdempotent() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        String token = login.getBody().data().token();
        assertThat(call(HttpMethod.POST, "/api/admin/auth/logout", null, token, VOID)
                .getBody().code()).isZero();
        // 令牌是无状态的：登出后仍可继续用（前端清掉即可），再次登出也不报错
        assertThat(call(HttpMethod.POST, "/api/admin/auth/logout", null, token, VOID)
                .getBody().code()).isZero();
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
