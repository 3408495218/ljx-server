package com.ljx.server.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 管理员账号。与玩家 {@code account} 表**彻底隔离**：代码里没有任何注册入口，
 * 首个管理员由 {@code LJX_ADMIN_USERNAME} / {@code LJX_ADMIN_PASSWORD} 在首次启动时创建。
 */
@Entity
@Table(name = "admin_user")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 32)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * 是否必须先改密码才能使用后台。
     * 首次创建（由 LJX_ADMIN_* 环境变量）与运维重置密码后都会置为 true；
     * 管理员本人改过密码后清掉。
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    protected AdminUser() {
    }

    /** 由环境变量首次创建：初始密码来自运维配置（常为默认值），因此**要求首次登录后必须修改** */
    public AdminUser(String username, String passwordHash, LocalDateTime createdAt) {
        this(username, passwordHash, createdAt, true);
    }

    /**
     * 显式指定是否需要强制改密码。
     * <p>
     * 供两类场景：① 运维已确认该账号无需强制改（例如从受控渠道下发的随机强密码）；
     * ② **集成测试** —— 测试账号不需要走"先改密码才能用后台"的流程，
     * 强制改密码本身另有专门的用例覆盖。
     */
    public AdminUser(String username, String passwordHash, LocalDateTime createdAt,
                     boolean mustChangePassword) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.mustChangePassword = mustChangePassword;
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

    public void markLogin(LocalDateTime at) {
        this.lastLoginAt = at;
    }

    /** 管理员本人修改密码：同时清掉「必须修改」标记 */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
    }

    /** 运维重置密码：重新要求本人登录后修改 */
    public void resetPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
