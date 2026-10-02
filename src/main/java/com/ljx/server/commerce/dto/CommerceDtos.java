package com.ljx.server.commerce.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class CommerceDtos {

    private CommerceDtos() {
    }

    /**
     * 商品。**图标与效果都由后端给出**，前端照着渲染即可，不再硬编码商品列表。
     * effectKind：TOP_CARD（置顶卡）/ VIP / NONE；durationDays 为空表示永久。
     */
    public record ShopItemDto(Long id, String name, String category, int priceCoins, String description,
                              String iconUrl, String effectKind, Integer durationDays, Integer vipLevel) {
    }

    /** 商城首页：钻石、当前 VIP 情况、可购买商品 */
    public record ShopDto(int coins, int vipLevel, String vipName, LocalDateTime vipExpiresAt,
                          List<ShopItemDto> items) {
    }

    /**
     * VIP 档位（**VIP 的唯一来源**：登录后买档位，房间边框随之改变）。
     * packageMaxMb / borderUrl / iconUrl 都由后台设置，前端不硬编码。
     */
    public record VipPlanDto(int level, String name, int priceCoins, String description,
                             int packageMaxMb, String borderUrl, String iconUrl,
                             /** 购买后的有效天数；null 表示永久。由后台设置 */
                             Integer durationDays) {
    }

    /** currentName 为当前账号所处档位的名称；expiresAt 为空表示从未购买或永久 */
    public record VipDto(int currentLevel, String currentName, int coins, LocalDateTime expiresAt,
                         List<VipPlanDto> plans) {
    }

    /** 购买：VIP 不需要 roomId；置顶卡必须给 roomId，且必须是自己名下的房间 */
    public record PurchaseRequest(@NotNull(message = "请选择商品") Long itemId, Long roomId) {
    }

    public record PurchaseResponse(int coins, String itemName, String message, LocalDateTime expiresAt) {
    }

    /** 购买 VIP：只给档位号（档位定义在 vip_plan，不在商品表里重复） */
    public record VipPurchaseRequest(
            @NotNull(message = "请选择 VIP 档位")
            @jakarta.validation.constraints.Min(value = 1, message = "档位不合法")
            Integer level) {
    }
}