package com.ljx.server.cdk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 兑换记录：既是"防重复领取"的依据（同 cdk + 同账号唯一），也是追溯凭据。
 * 删除 CDK 时若有记录在，应拒绝删除而不是级联删掉历史。
 */
@Entity
@Table(name = "cdk_redeem")
public class CdkRedeem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cdk_id", nullable = false)
    private Long cdkId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "coins", nullable = false)
    private int coins;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    protected CdkRedeem() {
    }

    public CdkRedeem(Long cdkId, Long accountId, int coins, LocalDateTime redeemedAt) {
        this.cdkId = cdkId;
        this.accountId = accountId;
        this.coins = coins;
        this.redeemedAt = redeemedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCdkId() {
        return cdkId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public int getCoins() {
        return coins;
    }

    public LocalDateTime getRedeemedAt() {
        return redeemedAt;
    }
}
