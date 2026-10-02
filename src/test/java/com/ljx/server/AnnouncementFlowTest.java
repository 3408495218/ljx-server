package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementDto;
import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementRequest;
import com.ljx.server.announcement.dto.AnnouncementDtos.PublicAnnouncementDto;
import com.ljx.server.announcement.dto.AnnouncementDtos.PublicAnnouncementListDto;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 公告：玩家端公开只读，后台可增删改；关闭/删除后玩家端立刻不可见。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnnouncementFlowTest {

    private static final String ADMIN_NAME = "ann_tester";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<ApiResponse<Integer>> ONE_INT =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PublicAnnouncementListDto>> PUBLIC_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<AnnouncementDto>>> ADMIN_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AnnouncementDto>> ADMIN_ONE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
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
    void adminCrudIsVisibleToPlayersUntilDisabledOrDeleted() {
        String token = adminToken();

        // 新建两条，排序决定玩家端顺序
        long second = create(token, "第二条公告", 20);
        long first = create(token, "第一条公告", 10);

        // 玩家端公开可读（不带任何令牌），按 sort_order 升序
        var publicList = call(HttpMethod.GET, "/api/announcements", null, null, PUBLIC_LIST);
        assertThat(publicList.getBody().code()).isZero();
        assertThat(publicList.getBody().data().items())
                .extracting(PublicAnnouncementDto::content)
                .containsSubsequence("第一条公告", "第二条公告");

        // 关闭其中一条 → 玩家端立刻不可见
        assertThat(call(HttpMethod.PUT, "/api/admin/announcements/" + second, 
                new AnnouncementRequest(null, null, false), token, ADMIN_ONE)
                .getBody().code()).isZero();
        assertThat(call(HttpMethod.GET, "/api/announcements", null, null, PUBLIC_LIST)
                .getBody().data().items())
                .extracting(PublicAnnouncementDto::content)
                .doesNotContain("第二条公告");

        // 删除另一条 → 玩家端也不可见
        assertThat(call(HttpMethod.DELETE, "/api/admin/announcements/" + first, null, token, VOID)
                .getBody().code()).isZero();
        assertThat(call(HttpMethod.GET, "/api/announcements", null, null, PUBLIC_LIST)
                .getBody().data().items())
                .extracting(PublicAnnouncementDto::content)
                .doesNotContain("第一条公告");
    }

    @Test
    void emptyContentIsRejectedAndMissingIdReturns1507() {
        String token = adminToken();
        assertThat(call(HttpMethod.POST, "/api/admin/announcements",
                new AnnouncementRequest("   ", 0, true), token, ADMIN_ONE).getBody().code())
                .isEqualTo(1001);
        assertThat(call(HttpMethod.DELETE, "/api/admin/announcements/999999", null, token, VOID)
                .getBody().code()).isEqualTo(1507);
    }

    @Test
    void playerApiNeedsNoTokenButAdminApiDoes() {
        // 玩家端：完全不需要令牌
        assertThat(call(HttpMethod.GET, "/api/announcements", null, null, PUBLIC_LIST)
                .getBody().code()).isZero();
        // 后台：没令牌一律 1104
        assertThat(call(HttpMethod.GET, "/api/admin/announcements", null, null, ADMIN_LIST)
                .getBody().code()).isEqualTo(1104);
    }

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    private long create(String token, String content, int sortOrder) {
        var res = call(HttpMethod.POST, "/api/admin/announcements",
                new AnnouncementRequest(content, sortOrder, true), token, ADMIN_ONE);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().id();
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

    /** 轮播间隔：默认 8 秒，可改成 3-120 之间的任意值，越界被拒；玩家端随公告一起拿到 */
    @Test
    void announcementRotateSecondsIsConfigurable() {
        String token = adminToken();

        // 默认值
        assertThat(call(HttpMethod.GET, "/api/admin/announcements/rotate-seconds", null, token, ONE_INT)
                .getBody().data()).isEqualTo(8);

        // 改成 20 秒
        assertThat(call(HttpMethod.PUT, "/api/admin/announcements/rotate-seconds",
                Map.of("seconds", 20), token, ONE_INT).getBody().data()).isEqualTo(20);
        assertThat(call(HttpMethod.GET, "/api/admin/announcements/rotate-seconds", null, token, ONE_INT)
                .getBody().data()).isEqualTo(20);

        // 越界被拒（3-120）
        assertThat(call(HttpMethod.PUT, "/api/admin/announcements/rotate-seconds",
                Map.of("seconds", 2), token, ONE_INT).getBody().code()).isEqualTo(1001);
        assertThat(call(HttpMethod.PUT, "/api/admin/announcements/rotate-seconds",
                Map.of("seconds", 200), token, ONE_INT).getBody().code()).isEqualTo(1001);

        // 玩家端公开接口能读到间隔（客户端据此轮播，不再写死 8 秒）
        var payload = call(HttpMethod.GET, "/api/announcements", null, null, PUBLIC_LIST).getBody().data();
        assertThat(payload.rotateSeconds()).isEqualTo(20);

        // 这里不改回默认值：本用例所在上下文与其他测试隔离（每个 @SpringBootTest 的库状态另论），
        // 但为稳妥仍复位，避免影响同上下文内的其他用例
        call(HttpMethod.PUT, "/api/admin/announcements/rotate-seconds", Map.of("seconds", 8), token, ONE_INT);
    }
}
