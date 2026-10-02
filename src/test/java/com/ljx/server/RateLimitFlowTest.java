package com.ljx.server;

import com.ljx.server.auth.dto.AuthDtos.LoginRequest;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4：认证接口按 IP 限流。
 * 其余测试类通过 src/test/resources/application.properties 关闭限流（都来自 127.0.0.1 会互相干扰），
 * 本类用独立上下文把阈值压到 2 次来验证行为。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ljx.rate-limit.enabled=true",
                "ljx.rate-limit.register.limit=2",
                "ljx.rate-limit.login.limit=2"
        })
class RateLimitFlowTest {

    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> TOKEN =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Test
    void registerAndLoginAreRateLimitedPerIp() {
        assertThat(register("rl_a").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(register("rl_b").getStatusCode()).isEqualTo(HttpStatus.OK);

        // 第 3 次注册超出窗口阈值
        ResponseEntity<ApiResponse<TokenResponse>> blocked = register("rl_c");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody().code()).isEqualTo(1601);

        // 登录计数独立于注册：前两次走到凭据校验，第三次被限流
        assertThat(login("rl_a", "wrong-password").getBody().code()).isEqualTo(1102);
        assertThat(login("rl_a", "wrong-password").getBody().code()).isEqualTo(1102);

        ResponseEntity<ApiResponse<TokenResponse>> blockedLogin = login("rl_a", "password123");
        assertThat(blockedLogin.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blockedLogin.getBody().code()).isEqualTo(1601);
    }

    private ResponseEntity<ApiResponse<TokenResponse>> register(String username) {
        return rest.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(new RegisterRequest(username, "password123")), TOKEN);
    }

    private ResponseEntity<ApiResponse<TokenResponse>> login(String username, String password) {
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(new LoginRequest(username, password)), TOKEN);
    }
}