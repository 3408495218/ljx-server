package com.ljx.server.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class AdminShopDtos {

    /** 后台看商品：比玩家端多 enabled（上下架）与效果字段，便于直接编辑 */
    public record AdminShopItemDto(
            Long id,
            String name,
            String category,
            int priceCoins,
            String description,
            String iconUrl,
            String effectKind,
            Integer durationDays,
            Integer vipLevel,
            boolean enabled) {
    }

    /** 改商品：**null 表示该字段不变**（与项目其他 Update 请求同一口径） */
    public record ShopItemUpdateRequest(
            @Min(value = 0, message = "价格不能为负") Integer priceCoins,
            @Min(value = 0, message = "时长不能为负") Integer durationDays,
            Boolean enabled,
            @Size(max = 160) String iconUrl) {
    }

    /** 后台看 VIP 档位：含时长、上传上限、边框与图标资源键 */
    public record AdminVipPlanDto(
            int level,
            String name,
            int priceCoins,
            String description,
            int packageMaxMb,
            String borderUrl,
            String iconUrl,
            Integer durationDays) {
    }

    /** 生效中的置顶卡：后台可看到哪个房间置顶到什么时候 */
    public record ActiveTopCardDto(
            Long roomId, String roomName, String ownerName, java.time.LocalDateTime expiresAt) {
    }

    /** 生效中的 VIP：后台可看到哪个账号是什么档位、什么时候到期 */
    public record ActiveVipDto(
            Long accountId, String username, int vipLevel, String vipName,
            java.time.LocalDateTime expiresAt) {
    }

    /**
     * 调整到期时间：expiresAt 传 null 表示**立即撤销**该效果。
     * <p>
     * 上限校验（不能超过 10 年）在 Service 里做：这里放注解会与"null 表示撤销"的语义纠缠，
     * 而且 {@code @Future} 之类的注解会连"合法缩短有效期"也一起拒掉。
     */
    public record EffectExpiryRequest(java.time.LocalDateTime expiresAt) {
    }

    /** 改 VIP 档位：null 表示该字段不变；传空串表示清空该字段 */
    public record VipPlanUpdateRequest(
            @Min(value = 0, message = "价格不能为负") Integer priceCoins,
            @Min(value = 0, message = "时长不能为负") Integer durationDays,
            @Min(value = 0, message = "上传上限不能为负") Integer packageMaxMb,
            @Size(max = 160) String borderUrl,
            @Size(max = 160) String iconUrl,
            @Size(max = 255) String description) {
    }
}
