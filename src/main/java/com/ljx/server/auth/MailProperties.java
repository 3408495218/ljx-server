package com.ljx.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 邮箱验证码的 SMTP 通道配置。
 * <p>
 * 默认关闭：开发期由 {@link LogMailSender} 把验证码打到日志；生产置 {@code enabled=true}
 * 并配置账号（QQ 邮箱用授权码而非登录密码，见 application.yml 注释）。
 */
@ConfigurationProperties("ljx.mail")
public class MailProperties {

    private boolean enabled = false;

    private String host = "smtp.qq.com";

    private int port = 465;

    private String username = "";

    private String password = "";

    /** 发件人；留空时取 username */
    private String from = "";

    /** QQ 邮箱 465 端口为隐式 SSL；改用 587 时置 false 走 STARTTLS */
    private boolean sslEnabled = true;

    private Duration connectTimeout = Duration.ofSeconds(10);

    private Duration readTimeout = Duration.ofSeconds(10);

    /** 发件人地址：显式配置优先，否则回落到登录账号 */
    public String senderAddress() {
        return from == null || from.isBlank() ? username : from;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

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

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}