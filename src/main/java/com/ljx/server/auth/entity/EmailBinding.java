package com.ljx.server.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_binding")
public class EmailBinding {

    @Id
    private Long accountId;

    private String email;

    private String codeHash;

    private LocalDateTime codeExpiresAt;

    private LocalDateTime codeSentAt;

    private boolean verified;

    protected EmailBinding() {
    }

    public EmailBinding(Long accountId) {
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public LocalDateTime getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public LocalDateTime getCodeSentAt() {
        return codeSentAt;
    }

    public boolean isVerified() {
        return verified;
    }

    public void sendCode(String email, String codeHash, LocalDateTime expiresAt, LocalDateTime sentAt) {
        this.email = email;
        this.codeHash = codeHash;
        this.codeExpiresAt = expiresAt;
        this.codeSentAt = sentAt;
    }

    public void markVerified(String email) {
        this.email = email;
        this.verified = true;
    }
}
