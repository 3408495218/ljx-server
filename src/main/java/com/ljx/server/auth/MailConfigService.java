package com.ljx.server.auth;

import com.ljx.server.appconfig.AppConfig;
import com.ljx.server.appconfig.AppConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 邮件 SMTP 配置的读取与保存。
 * <p>
 * **优先级：环境变量 > 后台配置**。这样两种部署方式都支持：
 * <ul>
 *   <li>喜欢用环境变量（生产推荐，凭据不进库）：设 {@code LJX_MAIL_USERNAME} / {@code LJX_MAIL_PASSWORD} 即可，后台面板会显示"由环境变量提供"；</li>
 *   <li>喜欢在后台点着配：写进 {@code app_config}，改完**立刻生效**（发送时才解析配置，不依赖重启）。</li>
 * </ul>
 * 密码**永不回显**：面板只告诉我们"是否已设置"。
 */
@Service
public class MailConfigService {

    /** app_config 里的键 */
    static final String KEY_ENABLED = "mail_enabled";
    static final String KEY_HOST = "mail_host";
    static final String KEY_PORT = "mail_port";
    static final String KEY_USERNAME = "mail_username";
    static final String KEY_PASSWORD = "mail_password";
    static final String KEY_FROM = "mail_from";
    static final String KEY_SSL = "mail_ssl";

    private final AppConfigRepository appConfigRepository;
    private final MailProperties props;

    public MailConfigService(AppConfigRepository appConfigRepository, MailProperties props) {
        this.appConfigRepository = appConfigRepository;
        this.props = props;
    }

    /** 一份可用的 SMTP 配置（不含"是否启用"的判断） */
    public record SmtpSettings(String host, int port, String username, String password,
                               String from, boolean sslEnabled, String source) {
    }

    /**
     * 当前生效的 SMTP 配置；返回 empty 表示没配（此时走日志通道）。
     * **每次发送都重新解析**，所以后台改完不需要重启。
     */
    public Optional<SmtpSettings> current() {
        // ① 环境变量（生产推荐）：用户名与密码都注入时优先
        if (!props.getUsername().isBlank() && !props.getPassword().isBlank()) {
            return Optional.of(new SmtpSettings(props.getHost(), props.getPort(), props.getUsername(),
                    props.getPassword(), props.senderAddress(), props.isSslEnabled(), "环境变量"));
        }
        // ② 后台配置：至少要 host + username + password，且被显式开启
        if (!boolValue(KEY_ENABLED, false)) {
            return Optional.empty();
        }
        String host = stringValue(KEY_HOST, "").trim();
        String username = stringValue(KEY_USERNAME, "").trim();
        String password = stringValue(KEY_PASSWORD, "");
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            return Optional.empty();
        }
        int port = intValue(KEY_PORT, 465);
        String from = stringValue(KEY_FROM, "").trim();
        return Optional.of(new SmtpSettings(host, port, username, password,
                from.isEmpty() ? username : from, boolValue(KEY_SSL, true), "后台配置"));
    }

    public boolean isConfigured() {
        return current().isPresent();
    }

    /** 后台保存；password 传 null 表示**保持原值不变**（面板不回显密码，所以不能要求每次重填） */
    @Transactional
    public void save(boolean enabled, String host, Integer port, String username, String password,
                     String from, Boolean sslEnabled) {
        put(KEY_ENABLED, String.valueOf(enabled));
        if (host != null) {
            put(KEY_HOST, host.trim());
        }
        if (port != null) {
            put(KEY_PORT, String.valueOf(port));
        }
        if (username != null) {
            put(KEY_USERNAME, username.trim());
        }
        if (password != null) {
            put(KEY_PASSWORD, password);
        }
        if (from != null) {
            put(KEY_FROM, from.trim());
        }
        if (sslEnabled != null) {
            put(KEY_SSL, String.valueOf(sslEnabled));
        }
    }

    // ---------- 读 ----------

    public boolean storedEnabled() {
        return boolValue(KEY_ENABLED, false);
    }

    public String storedHost() {
        return stringValue(KEY_HOST, "");
    }

    public int storedPort() {
        return intValue(KEY_PORT, 465);
    }

    public String storedUsername() {
        return stringValue(KEY_USERNAME, "");
    }

    public String storedFrom() {
        return stringValue(KEY_FROM, "");
    }

    public boolean storedSsl() {
        return boolValue(KEY_SSL, true);
    }

    /** 是否已存过密码（**不回显密码本身**） */
    public boolean passwordSet() {
        return !stringValue(KEY_PASSWORD, "").isEmpty();
    }

    /** 环境变量是否提供了凭据（此时后台配置会被环境变量覆盖） */
    public boolean envProvided() {
        return !props.getUsername().isBlank() && !props.getPassword().isBlank();
    }

    // ---------- 底层读写 ----------

    private String stringValue(String key, String fallback) {
        return appConfigRepository.findById(key).map(AppConfig::getConfigValue).orElse(fallback);
    }

    private int intValue(String key, int fallback) {
        return appConfigRepository.intValue(key, fallback);
    }

    private boolean boolValue(String key, boolean fallback) {
        String raw = stringValue(key, null);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private void put(String key, String value) {
        AppConfig config = appConfigRepository.findById(key).orElse(null);
        if (config == null) {
            appConfigRepository.save(AppConfig.of(key, value));
        } else {
            config.setValue(value);
        }
    }
}
