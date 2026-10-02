package com.ljx.server.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 商城订单：谁、何时、买了什么、作用于哪个房间、何时过期。
 * <p>
 * 商品名称与价格**冗余存一份**（不靠 join 商品表）：商品后续改名或调价时，
 * 历史订单仍应保持当时的真实成交信息。
 */
@Entity
@Table(name = "shop_order")
public class ShopOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 道具订单指向 shop_item.id；VIP 订单为 null（VIP 来自 vip_plan，没有商品行） */
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    @Column(name = "price_coins", nullable = false)
    private int priceCoins;

    @Column(name = "effect_kind", nullable = false, length = 24)
    private String effectKind;

    /** 置顶卡作用的房间；VIP 为 null */
    @Column(name = "target_room_id")
    private Long targetRoomId;

    /** VIP 档位；置顶卡为 null */
    @Column(name = "vip_level")
    private Integer vipLevel;

    /** 效果到期时间；null 表示永久 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ShopOrder() {
    }

    public ShopOrder(Long accountId, Long itemId, String itemName, int priceCoins,
                     String effectKind, Long targetRoomId, Integer vipLevel,
                     LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.accountId = accountId;
        this.itemId = itemId;
        this.itemName = itemName;
        this.priceCoins = priceCoins;
        this.effectKind = effectKind;
        this.targetRoomId = targetRoomId;
        this.vipLevel = vipLevel;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getItemName() {
        return itemName;
    }

    public int getPriceCoins() {
        return priceCoins;
    }

    public String getEffectKind() {
        return effectKind;
    }

    public Long getTargetRoomId() {
        return targetRoomId;
    }

    public Integer getVipLevel() {
        return vipLevel;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
