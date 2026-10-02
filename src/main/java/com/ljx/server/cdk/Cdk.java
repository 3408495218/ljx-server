package com.ljx.server.cdk;

import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * CDK 兑换码：玩家在商城输入码 → 兑换成金币（钻石）。
 * <p>
 * {@code totalUses = 0} 表示**不限次数**；但同一账号对同一个码**始终只能兑一次**
 * （由 {@code cdk_redeem} 的唯一约束保证），避免同一个码被同一个人反复刷。
 */
@Entity
@Table(name = "cdk")
public class Cdk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "coins", nullable = false)
    private int coins;

    /** 可兑换总次数；0 = 不限次 */
    @Column(name = "total_uses", nullable = false)
    private int totalUses;

    @Column(name = "used_uses", nullable = false)
    private int usedUses;

    /** 同一批生成的标记，便于筛选与导出 */
    @Column(name = "batch_no", length = 32)
    private String batchNo;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 空 = 永不过期 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Cdk() {
    }

    public Cdk(String code, int coins, int totalUses, String batchNo,
               LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.code = code;
        this.coins = coins;
        this.totalUses = totalUses;
        this.usedUses = 0;
        this.batchNo = batchNo;
        this.enabled = true;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /**
     * 码本身的可用性：是否启用、是否过期（**不含名额**）。
     * 拆成两步是为了让调用方能把"本账号已领过"插在中间——
     * 对同一个单次码重复兑换，说"你已经领过了"比"码被领完"更准确。
     */
    public void ensureActive(LocalDateTime now) {
        if (!enabled) {
            throw new BizException(ErrorCode.CDK_DISABLED);
        }
        if (expiresAt != null && now.isAfter(expiresAt)) {
            throw new BizException(ErrorCode.CDK_EXPIRED);
        }
    }

    /** 是否还有剩余名额（totalUses=0 为不限次） */
    public void ensureQuota() {
        if (totalUses > 0 && usedUses >= totalUses) {
            throw new BizException(ErrorCode.CDK_USED_UP);
        }
    }

    /** 占用一次名额（在行锁保护下调用） */
    public void consumeOnce() {
        this.usedUses += 1;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getCoins() {
        return coins;
    }

    public int getTotalUses() {
        return totalUses;
    }

    public int getUsedUses() {
        return usedUses;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
