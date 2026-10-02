package com.ljx.server;

import com.ljx.server.admin.AdminUser;
import com.ljx.server.admin.AdminUserRepository;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.admin.dto.AdminShopDtos.AdminShopItemDto;
import com.ljx.server.admin.dto.AdminShopDtos.AdminVipPlanDto;
import com.ljx.server.admin.dto.AdminShopDtos.EffectExpiryRequest;
import com.ljx.server.admin.dto.AdminShopDtos.ShopItemUpdateRequest;
import com.ljx.server.admin.dto.AdminShopDtos.VipPlanUpdateRequest;
import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.commerce.dto.CommerceDtos.VipPurchaseRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台商品管理：改价、改时长（过期时间的来源）、上下架、改 VIP 权益，
 * 以及**已售出效果**的查看 / 改到期 / 撤销。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminShopFlowTest extends GlobalConfigGuardTest {

    private static final String ADMIN_NAME = "shop_admin";
    private static final String ADMIN_PASSWORD = "secret123";

    private static final ParameterizedTypeReference<ApiResponse<AdminLoginResponse>> ADMIN_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<AdminShopItemDto>>> ITEMS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AdminShopItemDto>> ONE_ITEM =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<AdminVipPlanDto>>> PLANS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<AdminVipPlanDto>> ONE_PLAN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> PLAYER_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<java.util.Map<String, Object>>>> ACTIVE_VIPS =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AccountRepository accountRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void ensureAdmin() {
        if (adminUserRepository.findByUsername(ADMIN_NAME).isEmpty()) {
            adminUserRepository.save(new AdminUser(
                    ADMIN_NAME, encoder.encode(ADMIN_PASSWORD), LocalDateTime.now(), false));
        }
    }

    @Test
    void adminCanChangeItemPriceDurationAndShelfState() {
        String admin = adminToken();
        List<AdminShopItemDto> all = items(admin);
        // VIP 的唯一来源是 vip_plan：道具列表里出现 VIP 会让后台分不清去哪改（V17 已清掉历史遗留）
        assertThat(all).extracting(AdminShopItemDto::effectKind).doesNotContain("VIP");
        AdminShopItemDto topCard = all.stream()
                .filter(item -> "TOP_CARD".equals(item.effectKind())).findFirst().orElseThrow();

        int oldPrice = topCard.priceCoins();
        var res = call(HttpMethod.PUT, "/api/admin/shop/items/" + topCard.id(),
                new ShopItemUpdateRequest(oldPrice + 100, 15, false, null), admin, ONE_ITEM);
        assertThat(res.getBody().code()).isZero();
        assertThat(res.getBody().data().priceCoins()).isEqualTo(oldPrice + 100);
        assertThat(res.getBody().data().durationDays()).as("时长就是过期时间的来源").isEqualTo(15);
        assertThat(res.getBody().data().enabled()).as("可以下架").isFalse();

        // 下架后玩家端就看不到它了
        TokenResponse player = register("admin_shop_player");
        var shop = call(HttpMethod.GET, "/api/commerce/shop", null, player.accessToken(),
                new ParameterizedTypeReference<ApiResponse<com.ljx.server.commerce.dto.CommerceDtos.ShopDto>>() {
                });
        assertThat(shop.getBody().data().items()).isEmpty();

        // 复原，避免影响其他用例
        call(HttpMethod.PUT, "/api/admin/shop/items/" + topCard.id(),
                new ShopItemUpdateRequest(oldPrice, 7, true, null), admin, ONE_ITEM);
    }

    @Test
    void adminCanChangeVipPlanBenefits() {
        String admin = adminToken();
        AdminVipPlanDto iron = plans(admin).stream()
                .filter(plan -> plan.level() == 1).findFirst().orElseThrow();

        var res = call(HttpMethod.PUT, "/api/admin/shop/vip-plans/1",
                new VipPlanUpdateRequest(1500, 90, 120, "border-gold", "vip-gold", "改过的说明"),
                admin, ONE_PLAN);
        assertThat(res.getBody().code()).isZero();
        assertThat(res.getBody().data().priceCoins()).isEqualTo(1500);
        assertThat(res.getBody().data().durationDays()).isEqualTo(90);
        assertThat(res.getBody().data().packageMaxMb()).as("上传上限由后台设置").isEqualTo(120);

        // 玩家端立刻能看到新价格与新时长
        TokenResponse player = register("admin_plan_player");
        var vip = call(HttpMethod.GET, "/api/commerce/vip", null, player.accessToken(),
                new ParameterizedTypeReference<ApiResponse<com.ljx.server.commerce.dto.CommerceDtos.VipDto>>() {
                });
        var changed = vip.getBody().data().plans().stream()
                .filter(plan -> plan.level() == 1).findFirst().orElseThrow();
        assertThat(changed.priceCoins()).isEqualTo(1500);
        assertThat(changed.packageMaxMb()).isEqualTo(120);

        // 复原
        call(HttpMethod.PUT, "/api/admin/shop/vip-plans/1",
                new VipPlanUpdateRequest(1000, 30, 50, "border-iron", "vip-iron",
                        "房间边框：铁块；客户端压缩包上限 50MB"), admin, ONE_PLAN);
    }

    @Test
    void defaultTierCannotBeEdited() {
        // 普通用户(0 档)是默认档位、不可购买，改它没有意义
        assertThat(call(HttpMethod.PUT, "/api/admin/shop/vip-plans/0",
                new VipPlanUpdateRequest(1, 1, 1, null, null, null), adminToken(), ONE_PLAN)
                .getBody().code()).isEqualTo(1514);
    }

    /** 生效中的 VIP 可以查看、改到期时间、撤销 */
    @Test
    void adminCanSeeAndChangeActiveVipExpiry() {
        String admin = adminToken();
        TokenResponse player = register("admin_effect_player");
        long accountId = player.account().id();

        Account account = accountRepository.findById(accountId).orElseThrow();
        account.addCoins(5000);
        accountRepository.saveAndFlush(account);

        // 买铁块VIP
        var buy = call(HttpMethod.POST, "/api/commerce/vip/purchase",
                new VipPurchaseRequest(1), player.accessToken(), VOID);
        assertThat(buy.getBody().code()).isZero();

        // 出现在"生效中的 VIP"里
        var active = call(HttpMethod.GET, "/api/admin/shop/effects/vips", null, admin, ACTIVE_VIPS);
        assertThat(active.getBody().code()).isZero();
        assertThat(active.getBody().data())
                .anySatisfy(row -> assertThat(row.get("accountId")).isEqualTo((int) accountId));

        // 改到期时间（覆盖）
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(365).withNano(0);
        assertThat(call(HttpMethod.PUT, "/api/admin/shop/effects/vips/" + accountId,
                new EffectExpiryRequest(newExpiry), admin, VOID).getBody().code()).isZero();
        assertThat(accountRepository.findById(accountId).orElseThrow().getVipExpiresAt())
                .isEqualTo(newExpiry);

        // 撤销（传 null）→ 档位与到期一起清掉
        assertThat(call(HttpMethod.PUT, "/api/admin/shop/effects/vips/" + accountId,
                new EffectExpiryRequest(null), admin, VOID).getBody().code()).isZero();
        Account revoked = accountRepository.findById(accountId).orElseThrow();
        assertThat(revoked.getVipLevel()).isZero();
        assertThat(revoked.getVipExpiresAt()).isNull();

        // 撤销后不再出现在生效列表
        var after = call(HttpMethod.GET, "/api/admin/shop/effects/vips", null, admin, ACTIVE_VIPS);
        assertThat(after.getBody().data())
                .noneSatisfy(row -> assertThat(row.get("accountId")).isEqualTo((int) accountId));
    }

    @Test
    void shopAdminApiRequiresToken() {
        assertThat(call(HttpMethod.GET, "/api/admin/shop/items", null, null, ITEMS)
                .getBody().code()).isEqualTo(1104);
        assertThat(call(HttpMethod.GET, "/api/admin/shop/effects/vips", null, null, ACTIVE_VIPS)
                .getBody().code()).isEqualTo(1104);
    }

    // ---------- 辅助 ----------

    private String adminToken() {
        var login = call(HttpMethod.POST, "/api/admin/auth/login",
                new AdminLoginRequest(ADMIN_NAME, ADMIN_PASSWORD), null, ADMIN_TOKEN);
        assertThat(login.getBody().code()).isZero();
        return login.getBody().data().token();
    }

    private List<AdminShopItemDto> items(String admin) {
        var res = call(HttpMethod.GET, "/api/admin/shop/items", null, admin, ITEMS);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private List<AdminVipPlanDto> plans(String admin) {
        var res = call(HttpMethod.GET, "/api/admin/shop/vip-plans", null, admin, PLANS);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private TokenResponse register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest(username, "password123"), null, PLAYER_TOKEN);
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
