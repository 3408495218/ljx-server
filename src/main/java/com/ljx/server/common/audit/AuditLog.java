package com.ljx.server.common.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 审计流水：账号操作留痕，用于安全排查与运营追溯 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    private static final int IP_MAX = 64;
    private static final int DETAIL_MAX = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 未登录或用户名不存在时为 null */
    private Long accountId;

    private String ip;

    @Enumerated(EnumType.STRING)
    private AuditAction action;

    /** 摘要：房间名 / 文件名 / 尝试的用户名等 */
    private String detail;

    private boolean success;

    private LocalDateTime createdAt;

    protected AuditLog() {
    }

    public AuditLog(Long accountId, String ip, AuditAction action, String detail, boolean success) {
        this.accountId = accountId;
        this.ip = truncate(ip, IP_MAX);
        this.action = action;
        this.detail = truncate(detail, DETAIL_MAX);
        this.success = success;
        this.createdAt = LocalDateTime.now();
    }

    /** 列宽有硬上限，超长摘要在此截断，避免写入失败牵连业务事务 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getIp() {
        return ip;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isSuccess() {
        return success;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}