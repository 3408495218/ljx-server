package com.ljx.server;

import com.ljx.server.auth.MailSender;
import com.ljx.server.auth.dto.AuthDtos.AccountDto;
import com.ljx.server.auth.dto.AuthDtos.BindEmailRequest;
import com.ljx.server.auth.dto.AuthDtos.LoginRequest;
import com.ljx.server.auth.dto.AuthDtos.RefreshRequest;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.SendCodeRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest {

    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AccountDto>> ACCOUNT =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private MailSender mailSender;

    @Test
    void registerLoginMeAndRefreshRotation() {
        var reg = post("/api/auth/register", new RegisterRequest("steve", "password123"), TOKEN);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reg.getBody().code()).isZero();
        assertThat(reg.getBody().data().accessToken()).isNotBlank();
        assertThat(reg.getBody().data().refreshToken()).isNotBlank();
        assertThat(reg.getBody().data().account().quotas())
                .hasSize(2)
                .containsEntry("CLIENT_PKG_MB", 5)
                // 容量由房主自设，生效值为 UNLIMITED
                .containsEntry("PLAYER_CAP", Integer.MAX_VALUE);

        var dup = post("/api/auth/register", new RegisterRequest("steve", "password123"), TOKEN);
        assertThat(dup.getBody().code()).isEqualTo(1101);

        var badLogin = post("/api/auth/login", new LoginRequest("steve", "wrong-password"), TOKEN);
        assertThat(badLogin.getBody().code()).isEqualTo(1102);

        var login = post("/api/auth/login", new LoginRequest("steve", "password123"), TOKEN);
        assertThat(login.getBody().code()).isZero();
        var tokens = login.getBody().data();

        var anon = rest.getForEntity("/api/me", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var me = rest.exchange("/api/me", HttpMethod.GET, authed(tokens.accessToken(), null), ACCOUNT);
        assertThat(me.getBody().code()).isZero();
        assertThat(me.getBody().data().username()).isEqualTo("steve");
        assertThat(me.getBody().data().email()).isNull();

        var rotated = post("/api/auth/refresh", new RefreshRequest(tokens.refreshToken()), TOKEN);
        assertThat(rotated.getBody().code()).isZero();

        var replay = post("/api/auth/refresh", new RefreshRequest(tokens.refreshToken()), TOKEN);
        assertThat(replay.getBody().code()).isEqualTo(1103);

        var afterReplay = post("/api/auth/refresh",
                new RefreshRequest(rotated.getBody().data().refreshToken()), TOKEN);
        assertThat(afterReplay.getBody().code()).isEqualTo(1103);
    }

    @Test
    void emailBindingFlow() {
        var reg = post("/api/auth/register", new RegisterRequest("alex", "password123"), TOKEN);
        String access = reg.getBody().data().accessToken();

        var code = rest.exchange("/api/me/email/code", HttpMethod.POST,
                authed(access, new SendCodeRequest("alex@example.com")), VOID);
        assertThat(code.getBody().code()).isZero();

        ArgumentCaptor<String> verifyCode = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq("alex@example.com"), eq("垃圾侠邮箱验证码"), verifyCode.capture());

        var wrongCode = rest.exchange("/api/me/email", HttpMethod.PUT,
                authed(access, new BindEmailRequest("alex@example.com", "000000")), ACCOUNT);
        assertThat(wrongCode.getBody().code()).isEqualTo(1202);

        var cooldown = rest.exchange("/api/me/email/code", HttpMethod.POST,
                authed(access, new SendCodeRequest("alex@example.com")), VOID);
        assertThat(cooldown.getBody().code()).isEqualTo(1203);

        var badEmail = rest.exchange("/api/me/email/code", HttpMethod.POST,
                authed(access, new SendCodeRequest("not-an-email")), VOID);
        assertThat(badEmail.getBody().code()).isEqualTo(1201);

        var bind = rest.exchange("/api/me/email", HttpMethod.PUT,
                authed(access, new BindEmailRequest("alex@example.com", verifyCode.getValue())), ACCOUNT);
        assertThat(bind.getBody().code()).isZero();
        assertThat(bind.getBody().data().email()).isEqualTo("alex@example.com");
    }

    private <T> ResponseEntity<ApiResponse<T>> post(String path, Object body,
                                                    ParameterizedTypeReference<ApiResponse<T>> type) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body), type);
    }

    private HttpEntity<Object> authed(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }
}
