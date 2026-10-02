package com.ljx.server;

import com.ljx.server.auth.dto.AuthDtos.AccountDto;
import com.ljx.server.auth.dto.AuthDtos.LoginRequest;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.commerce.ScoreEventType;
import com.ljx.server.commerce.ScoreService;
import com.ljx.server.commerce.dto.CommerceDtos.ShopDto;
import com.ljx.server.commerce.dto.CommerceDtos.VipDto;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditLog;
import com.ljx.server.common.audit.AuditLogRepository;
import com.ljx.server.room.dto.RoomDtos.CreateRoomRequest;
import com.ljx.server.room.dto.RoomDtos.RoomDetailDto;
import com.ljx.server.room.dto.RoomDtos.UpdateRoomRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** B4：商城 / VIP 档位查询、容量不受 VIP 限制、每日活跃、审计留痕 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommerceFlowTest {

    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AccountDto>> ACCOUNT =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<RoomDetailDto>> DETAIL =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<ShopDto>> SHOP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<VipDto>> VIP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EmailBindingRepository emailBindingRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ScoreService scoreService;

    @Test
    void shopCatalogAndVipTiersAreReadable() {
        String token = register("shop_user").accessToken();

        ShopDto shop = call(HttpMethod.GET, "/api/commerce/shop", token, null, SHOP).getBody().data();
        assertThat(shop.coins()).isZero();
        // 道具商城只放道具（V13 起 VIP 归 vip_plan，不在商品列表里重复出现）
        assertThat(shop.items()).hasSize(1);
        assertThat(shop.items()).extracting("name").containsExactly("置顶卡");
        assertThat(shop.items()).extracting("effectKind").containsOnly("TOP_CARD");
        assertThat(shop.items()).allSatisfy(item -> {
            assertThat(item.iconUrl()).as("商品图标用资源键，图片打包在桌面端").isEqualTo("top-card");
            assertThat(item.durationDays()).as("置顶卡有有效天数").isNotNull();
        });

        VipDto vip = call(HttpMethod.GET, "/api/commerce/vip", token, null, VIP).getBody().data();
        assertThat(vip.currentLevel()).isZero();
        assertThat(vip.currentName()).isEqualTo("普通用户");
        // V11 起 VIP 只有 0-3 档（普通 / 铁块 / 金块 / 钻石）
        assertThat(vip.plans()).hasSize(4);
        assertThat(vip.plans().get(0).level()).isZero();
        assertThat(vip.plans().get(3).level()).isEqualTo(3);
        assertThat(vip.plans().get(3).name()).isEqualTo("钻石VIP");
        // 三档 VIP 各自带着房间边框与上传限额
        assertThat(vip.plans().get(1).packageMaxMb()).isEqualTo(50);
        assertThat(vip.plans().get(2).packageMaxMb()).isEqualTo(75);
        assertThat(vip.plans().get(3).packageMaxMb()).isEqualTo(100);
        assertThat(vip.plans().get(1).borderUrl()).isEqualTo("border-iron");
        // 档位图标同样由后端给；普通用户(0 档)没有图标，前端不应拿 VIP 图标凑数
        assertThat(vip.plans().get(1).iconUrl()).isEqualTo("vip-iron");
        assertThat(vip.plans().get(0).iconUrl()).as("普通用户不显示档位图标").isNull();
    }

    @Test
    void roomCapacityIsSetByOwnerAndNotGatedByVip() {
        TokenResponse owner = registerVerified("cap_owner");
        String token = owner.accessToken();

        // 容量由服主自设，远超任何档位也不拦截
        assertThat(create(token, "Cap 10", 10).getBody().code()).isZero();
        assertThat(create(token, "Cap 999", 999).getBody().code()).isZero();

        long roomId = create(token, "Cap Room", 8).getBody().data().id();
        assertThat(updateCapacity(token, roomId, 300).getBody().code()).isZero();

        // 提升 VIP 不改变容量校验结果——两者本就不挂钩
        grantVip(owner.account().id(), 3);
        assertThat(updateCapacity(token, roomId, 500).getBody().code()).isZero();
        assertThat(create(token, "Cap 500", 500).getBody().code()).isZero();

        VipDto vip = call(HttpMethod.GET, "/api/commerce/vip", token, null, VIP).getBody().data();
        // V11 起最高档是钻石VIP(3)；且必须带到期时间才算生效
        assertThat(vip.currentLevel()).isEqualTo(3);
        assertThat(vip.currentName()).isEqualTo("钻石VIP");
    }

    // 注：原「每日活跃补发」用例已随 DailyActiveScheduler 一起删除 ——
    // 该机制与「每天登录一次获得一次经验」重复，会在同一天给同一账号发两次经验。
    // 「每日只发一次」的行为现在由 LevelFlowTest 覆盖（登录场景）。

    @Test
    void auditLogRecordsAuthAndRoomActions() {
        TokenResponse registered = register("audit_user");
        long accountId = registered.account().id();
        String token = registered.accessToken();

        assertThat(auditRows(accountId, AuditAction.REGISTER)).hasSize(1);

        // 登录失败与成功分别留痕
        call(HttpMethod.POST, "/api/auth/login", null,
                new LoginRequest("audit_user", "wrong-password"), TOKEN);
        call(HttpMethod.POST, "/api/auth/login", null,
                new LoginRequest("audit_user", "password123"), TOKEN);

        assertThat(auditRows(accountId, AuditAction.LOGIN_FAILED))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.isSuccess()).isFalse();
                    assertThat(row.getDetail()).isEqualTo("audit_user");
                    assertThat(row.getIp()).isNotBlank();
                });
        assertThat(auditRows(accountId, AuditAction.LOGIN)).hasSize(1);

        // 房间创建与删除留痕
        verifyEmail(accountId, "audit_user@example.com");
        long roomId = create(token, "Audit Room", 8).getBody().data().id();
        call(HttpMethod.DELETE, "/api/rooms/" + roomId, token, null, VOID);

        assertThat(auditRows(accountId, AuditAction.ROOM_CREATE))
                .singleElement()
                .satisfies(row -> assertThat(row.getDetail()).isEqualTo("Audit Room"));
        assertThat(auditRows(accountId, AuditAction.ROOM_DELETE))
                .singleElement()
                .satisfies(row -> assertThat(row.getDetail()).isEqualTo("Audit Room"));
    }

    // ---------- helpers ----------

    private List<AuditLog> auditRows(long accountId, AuditAction action) {
        return auditLogRepository.findByAccountIdAndActionOrderByIdDesc(accountId, action);
    }

    /**
     * 授予 VIP。注意必须带上到期时间——V11 起"档位大于 0 但到期时间为空（或已过期）"
     * 会被判为普通用户（读时判断，不靠定时任务）。
     */
    private void grantVip(long accountId, int vipLevel) {
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId).orElseThrow();
        account.grantVip(vipLevel, LocalDateTime.now().plusDays(30));
        accountRepository.save(account);
    }

    private AccountDto me(String token) {
        return call(HttpMethod.GET, "/api/me", token, null, ACCOUNT).getBody().data();
    }

    private ResponseEntity<ApiResponse<RoomDetailDto>> create(String token, String name, int capacity) {
        return call(HttpMethod.POST, "/api/rooms", token,
                new CreateRoomRequest(name, "intro", "Paper", "1.21.1", "生存", capacity,
                        false, false, false, null), DETAIL);
    }

    private ResponseEntity<ApiResponse<RoomDetailDto>> updateCapacity(String token, long roomId, int capacity) {
        return call(HttpMethod.PUT, "/api/rooms/" + roomId, token,
                new UpdateRoomRequest(null, null, null, null, null, capacity, null, null, null, null,
                        null, null, null, null), DETAIL);
    }

    private TokenResponse register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register", null,
                new RegisterRequest(username, "password123"), TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    /** 注册并直接写入已验证的邮箱绑定，用于通过「创建房间需绑定邮箱」门槛 */
    private TokenResponse registerVerified(String username) {
        TokenResponse token = register(username);
        verifyEmail(token.account().id(), username + "@example.com");
        return token;
    }

    private void verifyEmail(long accountId, String email) {
        EmailBinding binding = new EmailBinding(accountId);
        binding.markVerified(email);
        emailBindingRepository.save(binding);
    }

    private <T> ResponseEntity<ApiResponse<T>> call(HttpMethod method, String path, String token,
                                                    Object body,
                                                    ParameterizedTypeReference<ApiResponse<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}