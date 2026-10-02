package com.ljx.server.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台配置（{@code ljx.admin.*}）。
 * <p>
 * 首个管理员由 username / password 在首次启动且管理员表为空时创建；
 * 两者留空则**不创建**（**不内置任何默认密码**）。
 */
@Component
@ConfigurationProperties(prefix = "ljx.admin")
public class AdminProperties {

    /** 首个管理员用户名；留空表示不自动创建 */
    private String username = "";

    /** 首个管理员密码；只在启动时读一次，落库为 BCrypt */
    private String password = "";

    /** 管理端 JWT 密钥；留空则回退到 ljx.jwt.secret */
    private String jwtSecret = "";

    /** 管理端令牌有效期（比玩家端更短，且不提供 refresh） */
    private Duration tokenTtl = Duration.ofHours(2);

    /** 允许访问 /api/admin/** 的来源 IP；留空表示不限制（生产建议锁到内网） */
    private List<String> allowedIps = new ArrayList<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    /**
     * 启动时把生效的白名单打出来。
     * <p>
     * 为什么要专门打日志：这个配置一旦"没绑上"就会**静默变成不限制**——
     * 使用者以为锁了内网，实际完全开放。这类安全配置必须能从启动日志里确认。
     */
    @jakarta.annotation.PostConstruct
    public void reportAllowedIps() {
        org.slf4j.LoggerFactory.getLogger(AdminProperties.class).info(
                "管理端 IP 白名单：{}", allowedIps.isEmpty() ? "未限制（所有来源可访问）" : allowedIps);
    }

    public List<String> getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }
}
