package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseSnippetResponse;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseStatusDto;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseTestRequest;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseTestResponse;
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

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库面板：只读现状、当场试连、生成片段。
 * <p>
 * 两条最重要的约束用测试钉住：
 * ① **任何响应里都不能出现密码字段**（部署凭据不外泄）；
 * ② 试连失败要返回可读原因，**不能把异常抛给前端**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabasePanelFlowTest {

    private static final String ADMIN_NAME = "db_tester";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<DatabaseStatusDto>> STATUS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<DatabaseTestResponse>> TEST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<DatabaseSnippetResponse>> SNIPPET =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    @Test
    void statusShowsConnectionButNeverThePassword() {
        var res = call(HttpMethod.GET, "/api/admin/database", null, adminToken(), STATUS);
        assertThat(res.getBody().code()).isZero();
        DatabaseStatusDto status = res.getBody().data();

        assertThat(status.jdbcUrl()).as("应能读到实际生效的连接串").isNotBlank();
        assertThat(status.poolState()).isNotBlank();
        // 类型判定与结论提示：使用者要能一眼看出"现在用的是什么库、要不要换"
        assertThat(status.kind()).isIn("H2", "MySQL", "PostgreSQL");
        assertThat(status.kindHint()).as("必须给出结论性说明，而不只是原始参数").isNotBlank();
        // 持久化判定要与连接串一致：测试环境用的是 H2 内存库（:mem:），本地运行用的是文件库
        boolean inMemory = status.jdbcUrl().toLowerCase().contains(":mem:");
        assertThat(status.persistent())
                .as("内存库不得被判定为持久（否则会误导使用者）")
                .isEqualTo(!inMemory);
        if (inMemory) {
            assertThat(status.kindHint()).as("内存库必须给出数据会丢失的警告").contains("丢失");
        }
        assertThat(status.appliedMigrations()).as("H2 测试库也执行了 Flyway 迁移").isPositive();

        // 安全约束：DTO 里根本不能有 password 字段
        assertThat(Arrays.stream(DatabaseStatusDto.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("数据库状态响应不得包含密码字段")
                .doesNotContain("password", "passwd", "pwd");
    }

    @Test
    void testConnectionReturnsReadableReasonInsteadOfThrowing() {
        // 127.0.0.1:1 是必然连不上的端口；这里断言"返回失败原因"而不是抛异常
        var res = call(HttpMethod.POST, "/api/admin/database/test",
                new DatabaseTestRequest("127.0.0.1", 1, "some_db", "someone", "whatever"),
                adminToken(), TEST);
        assertThat(res.getBody().code()).as("接口本身应成功返回，而不是抛出异常").isZero();

        DatabaseTestResponse result = res.getBody().data();
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).as("失败原因要能指导使用者改配置").isNotBlank();
        assertThat(result.elapsedMs()).isNotNegative();
    }

    @Test
    void snippetUsesPlaceholdersAndNeverEchoesPassword() {
        var res = call(HttpMethod.POST, "/api/admin/database/snippet",
                new DatabaseTestRequest("db.example.com", 3306, "lianjixia", "ljx_user", "s3cret-pass"),
                adminToken(), SNIPPET);
        assertThat(res.getBody().code()).isZero();

        DatabaseSnippetResponse snippet = res.getBody().data();
        assertThat(snippet.yaml()).contains("jdbc:mysql://db.example.com:3306/lianjixia");
        assertThat(snippet.yaml()).contains("LJX_DB_PASSWORD");
        assertThat(snippet.envVars()).hasSize(3);
        // 关键：请求里带来的密码不得回显到任何地方
        assertThat(snippet.yaml()).doesNotContain("s3cret-pass");
        assertThat(String.join(" ", snippet.envVars())).doesNotContain("s3cret-pass");
        // 生成片段不等于改动配置
        assertThat(snippet.note()).contains("重启");
    }

    @Test
    void databasePanelRequiresAdminToken() {
        assertThat(call(HttpMethod.GET, "/api/admin/database", null, null, STATUS)
                .getBody().code()).isEqualTo(1104);
        assertThat(call(HttpMethod.POST, "/api/admin/database/test",
                new DatabaseTestRequest("127.0.0.1", 3306, "x", "y", "z"), null, TEST)
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
