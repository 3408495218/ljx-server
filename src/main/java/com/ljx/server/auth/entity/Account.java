package com.ljx.server.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    private String passwordHash;

    private int level;

    private int score;

    /** 最后一次领取每日登录经验的日期；同一天只能领一次 */
    private java.time.LocalDate lastDailyRewardOn;

    private int vipLevel;

    /** VIP 到期时间；null 表示从未购买。读取时判断，过期即按普通用户处理 */
    private LocalDateTime vipExpiresAt;

    private int coins;

    /**
     * 是否为「访客」账号：未登录时由客户端自动申请，仅用于承载房主身份。
     * 密码是随机不可猜值，因此这种账号无法被登录。
     */
    @Column(name = "anonymous", nullable = false)
    private boolean anonymous;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    protected Account() {
    }

    public Account(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** 分数与等级由 ScoreService 统一物化，等级只升不降 */
    /** VIP 档位变更：本期无自助购买通道（不做支付闭环），由运营侧调整 */
    public void applyVip(int vipLevel) {
        this.vipLevel = vipLevel;
    }

    /**
     * 购买/续费 VIP。
     * <p>
     * 规则：**未过期时档位只升不降、时长顺延；已过期时直接按新档生效**。
     * 后者很关键——如果已过期还沿用 {@code Math.max}，买铁块VIP 会保留此前过期的钻石档，
     * 等于花铁块的钱享受钻石权益。
     */
    public void grantVip(int level, LocalDateTime newExpiresAt) {
        LocalDateTime now = LocalDateTime.now();
        boolean stillValid = vipExpiresAt != null && vipExpiresAt.isAfter(now);
        this.vipLevel = stillValid ? Math.max(this.vipLevel, level) : level;

        LocalDateTime base = stillValid ? vipExpiresAt : now;
        this.vipExpiresAt = newExpiresAt == null
                ? null
                : base.plus(java.time.Duration.between(now, newExpiresAt));
    }

    public LocalDateTime getVipExpiresAt() {
        return vipExpiresAt;
    }

    /** 后台直接指定 VIP 到期时间（覆盖而不是叠加） */
    public void setVipExpiry(LocalDateTime expiresAt) {
        this.vipExpiresAt = expiresAt;
    }

    /** 立即撤销 VIP（档位与到期一起清掉） */
    public void revokeVip() {
        this.vipLevel = 0;
        this.vipExpiresAt = null;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getLevel() {
        return level;
    }

    public int getScore() {
        return score;
    }

    /**
     * 增加/扣减当前级内经验。升级时会扣掉该级所需经验（"需要 N 点经验升级"的口径），
     * 所以 score 表示的是**当前等级内的进度**，不扣成负数。
     */
    public void addScore(int delta) {
        if (delta < 0) {
            this.score = Math.max(0, this.score + delta);
        } else {
            this.score += delta;
        }
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public java.time.LocalDate getLastDailyRewardOn() {
        return lastDailyRewardOn;
    }

    /** 标记今天的每日经验已领取 */
    /** 标记为访客账号（由 guest 注册流程调用） */
    public void markAnonymous() {
        this.anonymous = true;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public void markDailyReward(java.time.LocalDate date) {
        this.lastDailyRewardOn = date;
    }

    public int getVipLevel() {
        return vipLevel;
    }

    public int getCoins() {
        return coins;
    }

    /** 加金币（CDK 兑换）；调用方需保证在同一事务内 */
    public void addCoins(int delta) {
        this.coins = Math.max(0, this.coins + delta);
    }

    /**
     * 扣金币（购买商品）。**余额不足直接抛异常**，不用 addCoins 的 Math.max——
     * 那样会把"超扣"静默变成归零，等于白送商品。
     */
    public void deductCoins(int amount) {
        if (amount <= 0) {
            throw new com.ljx.server.common.exception.BizException(
                    com.ljx.server.common.api.ErrorCode.VALIDATION_FAILED);
        }
        if (this.coins < amount) {
            throw new com.ljx.server.common.exception.BizException(
                    com.ljx.server.common.api.ErrorCode.INSUFFICIENT_COINS);
        }
        this.coins -= amount;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
