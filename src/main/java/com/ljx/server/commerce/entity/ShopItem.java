package com.ljx.server.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 商城商品目录项。**商品由后端发放**：前端只认这里返回的字段，不再硬编码商品。
 * <p>
 * {@code effectKind} 决定买下之后发生什么：
 * <ul>
 *   <li>{@code TOP_CARD} —— 把指定房间置顶（房间维度，见 room_top_card）</li>
 *   <li>{@code VIP} —— 提升账号 VIP 档位（账号维度，见 vip_plan）</li>
 *   <li>{@code NONE} —— 暂未实现效果的展示型商品</li>
 * </ul>
 */
@Entity
@Table(name = "shop_item")
public class ShopItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String category;

    private int priceCoins;

    private String description;

    private int sortOrder;

    /** TOP_CARD / VIP / NONE */
    @Column(name = "effect_kind", nullable = false, length = 24)
    private String effectKind;

    /** 商品图标的**资源键**（如 top-card）；图片打包在桌面端，前端按此映射 */
    @Column(name = "icon_url", length = 160)
    private String iconUrl;

    /** 效果时长（天）；null 表示永久 */
    @Column(name = "duration_days")
    private Integer durationDays;

    /** effectKind=VIP 时对应 vip_plan.level */
    @Column(name = "vip_level")
    private Integer vipLevel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected ShopItem() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public int getPriceCoins() {
        return priceCoins;
    }

    public String getDescription() {
        return description;
    }

    /** 后台改价 / 改时长 / 上下架（null 表示该字段不变） */
    public void update(Integer priceCoins, Integer durationDays, Boolean enabled, String iconUrl) {
        if (priceCoins != null) {
            this.priceCoins = priceCoins;
        }
        if (durationDays != null) {
            this.durationDays = durationDays;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (iconUrl != null) {
            this.iconUrl = iconUrl.isBlank() ? null : iconUrl;
        }
    }

    public String getEffectKind() {
        return effectKind;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public Integer getVipLevel() {
        return vipLevel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}