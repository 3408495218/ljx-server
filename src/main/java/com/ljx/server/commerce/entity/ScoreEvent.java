package com.ljx.server.commerce.entity;

import com.ljx.server.commerce.ScoreEventType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 分数流水：account.score / account.level 均可由该表重算 */
@Entity
@Table(name = "score_event")
public class ScoreEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long accountId;

    @Enumerated(EnumType.STRING)
    private ScoreEventType type;

    private int delta;

    private LocalDateTime createdAt;

    protected ScoreEvent() {
    }

    /**
     * @param delta 本次实际加的经验。由调用方按后台配置算好后传入并留档，
     *              这样流水记录的是"当时真实加了多少"，不会因后续改配置而变化。
     */
    public ScoreEvent(Long accountId, ScoreEventType type, int delta) {
        this.accountId = accountId;
        this.type = type;
        this.delta = delta;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public ScoreEventType getType() {
        return type;
    }

    public int getDelta() {
        return delta;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}