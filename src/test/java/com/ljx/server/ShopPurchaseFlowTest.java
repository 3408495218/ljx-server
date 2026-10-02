package com.ljx.server;

import com.ljx.server.auth.dto.AuthDtos.RegisterRequest;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.commerce.dto.CommerceDtos.PurchaseRequest;
import com.ljx.server.commerce.dto.CommerceDtos.PurchaseResponse;
import com.ljx.server.commerce.dto.CommerceDtos.ShopDto;
import com.ljx.server.commerce.dto.CommerceDtos.ShopItemDto;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.quota.QuotaService;
import com.ljx.server.quota.QuotaType;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 购买闭环：扣钻石 → 生效 → 可追溯。
 * <p>
 * 重点覆盖三件容易出错的事：
 * ① 余额不足必须拒绝（不能白送）；② 置顶卡只能作用于**自己名下**的房间；
 * ③ **VIP 过期后买低档不能保留旧高档**（否则花铁块的钱享钻石权益）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShopPurchaseFlowTest {

    private static final ParameterizedTypeReference<ApiResponse<TokenResponse>> PLAYER_TOKEN =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<ShopDto>> SHOP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PurchaseResponse>> PURCHASE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<com.ljx.server.commerce.dto.CommerceDtos.VipDto>> VIP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void buyVipDeductsCoinsAndAppliesTier() {
        TokenResponse player = register("buy_vip");
        long accountId = player.account().id();
        // VIP 唯一来源是 vip_plan（不在商品表里），价格也从档位接口取
        int ironPrice = vipPlanPrice(player.accessToken(), 1);

        // 买不起 → 1510
        assertThat(vipPurchase(1, player.accessToken()).getBody().code()).isEqualTo(1510);

        refill(accountId, ironPrice + 500);
        var res = vipPurchase(1, player.accessToken());
        assertThat(res.getBody().code()).isZero();
        assertThat(res.getBody().data().coins()).as("应扣掉商品价格").isEqualTo(500);
        assertThat(res.getBody().data().expiresAt()).isNotNull();

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getVipLevel()).isEqualTo(1);
        assertThat(account.getVipExpiresAt()).isAfter(LocalDateTime.now());

        // 商城接口应反映当前 VIP（前端顶栏/商城都用它）
        ShopDto after = shop(player.accessToken());
        assertThat(after.vipLevel()).isEqualTo(1);
        assertThat(after.vipName()).isEqualTo("铁块VIP");
    }

    /** VIP 过期后再买低档，档位必须降到低档，不能沿用过期的高档 */
    @Test
    void expiredHighTierDoesNotSurviveBuyingLowerTier() {
        TokenResponse player = register("buy_downgrade");
        long accountId = player.account().id();

        // 直接写成"钻石VIP 但昨天就过期了"——用 grantVip 造不出这个状态
        // （它在未过期时会保留较高档位），所以这里直接改库
        jdbcTemplate.update("update account set vip_level = 3, vip_expires_at = ? where id = ?",
                LocalDateTime.now().minusDays(1), accountId);

        ShopDto shop = shop(player.accessToken());
        assertThat(shop.vipLevel()).as("已过期应显示为普通用户").isZero();

        refill(accountId, 5000);
        assertThat(vipPurchase(1, player.accessToken()).getBody().code()).isZero();

        Account after = accountRepository.findById(accountId).orElseThrow();
        assertThat(after.getVipLevel()).as("过期后买低档不应保留旧高档").isEqualTo(1);
        assertThat(after.getVipExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void topCardRequiresOwnRoom() {
        TokenResponse owner = register("buy_top_owner");
        TokenResponse other = register("buy_top_other");
        // 建房要求邮箱已验证，测试里直接写一条绑定，避免走邮件取码流程
        markEmailVerified(owner.account().id());
        markEmailVerified(other.account().id());
        long roomId = createRoom(other.accessToken(), "别人的房间");

        ShopDto shop = shop(owner.accessToken());
        // 道具商城里**只有道具**：VIP 不在其中（唯一来源是 vip_plan）
        assertThat(shop.items()).extracting(ShopItemDto::effectKind).doesNotContain("VIP");
        ShopItemDto topCard = item(shop, "置顶卡");
        refill(owner.account().id(), topCard.priceCoins() + 100);

        // 不传房间 → 1511
        assertThat(purchase(topCard.id(), null, owner.accessToken()).getBody().code()).isEqualTo(1511);
        // 传别人的房间 → 1512
        assertThat(purchase(topCard.id(), roomId, owner.accessToken()).getBody().code()).isEqualTo(1512);

        // 自己的房间 → 成功
        long myRoom = createRoom(owner.accessToken(), "我的房间");
        var res = purchase(topCard.id(), myRoom, owner.accessToken());
        assertThat(res.getBody().code()).isZero();
        assertThat(res.getBody().data().message()).contains("置顶");
        assertThat(res.getBody().data().expiresAt()).isNotNull();
    }

    /** 上传限额按 VIP 档位：无 VIP 用配额表默认值，有 VIP 取 vip_plan.package_max_mb */
    @Test
    void uploadLimitFollowsVipTier() {
        TokenResponse player = register("buy_quota");
        long accountId = player.account().id();

        int base = quotaService.effective(accountId, QuotaType.CLIENT_PKG_MB);
        assertThat(base).as("无 VIP 时用配额表里的默认值").isBetween(5, 30);

        int diamondPrice = vipPlanPrice(player.accessToken(), 3);
        refill(accountId, diamondPrice);
        assertThat(vipPurchase(3, player.accessToken()).getBody().code()).isZero();

        assertThat(quotaService.effective(accountId, QuotaType.CLIENT_PKG_MB))
                .as("钻石VIP 应把上传上限提到 100MB").isEqualTo(100);
    }

    // ---------- 辅助 ----------

    /** 从 VIP 档位表取价格（VIP 不走商品表） */
    private int vipPlanPrice(String token, int level) {
        var res = call(HttpMethod.GET, "/api/commerce/vip", null, token, VIP);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data().plans().stream()
                .filter(plan -> plan.level() == level).findFirst().orElseThrow().priceCoins();
    }

    private ShopItemDto item(ShopDto shop, String name) {
        return shop.items().stream().filter(one -> name.equals(one.name())).findFirst()
                .orElseThrow(() -> new AssertionError("商品不存在：" + name));
    }

    /** 建房要求邮箱已验证；测试里直接写一条已验证绑定，避免走邮件取码流程 */
    private void markEmailVerified(long accountId) {
        jdbcTemplate.update("delete from email_binding where account_id = ?", accountId);
        jdbcTemplate.update("insert into email_binding (account_id, email, verified) values (?, ?, 1)",
                accountId, "t" + accountId + "@example.com");
    }

    private void refill(long accountId, int coins) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.addCoins(coins);
        accountRepository.saveAndFlush(account);
    }

    private ShopDto shop(String token) {
        var res = call(HttpMethod.GET, "/api/commerce/shop", null, token, SHOP);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private ResponseEntity<ApiResponse<PurchaseResponse>> vipPurchase(int level, String token) {
        return call(HttpMethod.POST, "/api/commerce/vip/purchase",
                new com.ljx.server.commerce.dto.CommerceDtos.VipPurchaseRequest(level), token, PURCHASE);
    }

    private ResponseEntity<ApiResponse<PurchaseResponse>> purchase(Long itemId, Long roomId, String token) {
        return call(HttpMethod.POST, "/api/commerce/purchase", new PurchaseRequest(itemId, roomId), token, PURCHASE);
    }

    private TokenResponse register(String username) {
        var res = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest(username, "password123"), null, PLAYER_TOKEN);
        assertThat(res.getBody().code()).isZero();
        return res.getBody().data();
    }

    private long createRoom(String token, String name) {
        var res = call(HttpMethod.POST, "/api/rooms", new java.util.HashMap<>(java.util.Map.of(
                "name", name, "intro", "x", "core", "Paper-1.20.4", "mcVersion", "1.20.4",
                "mode", "生存", "capacity", 10, "locked", false,
                "noGuest", false, "needEmail", false)), token,
                new ParameterizedTypeReference<ApiResponse<com.ljx.server.room.dto.RoomDtos.RoomDetailDto>>() {
                });
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
}
