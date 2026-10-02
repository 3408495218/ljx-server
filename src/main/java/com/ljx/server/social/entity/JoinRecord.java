package com.ljx.server.social.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 加入记录，即大厅「足迹」视图的数据来源 */
@Entity
@Table(name = "join_record")
public class JoinRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long roomId;

    private Long accountId;

    private LocalDateTime joinedAt;

    protected JoinRecord() {
    }

    public JoinRecord(Long roomId, Long accountId) {
        this.roomId = roomId;
        this.accountId = accountId;
        this.joinedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }
}