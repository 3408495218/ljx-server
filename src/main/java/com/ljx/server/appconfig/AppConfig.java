package com.ljx.server.appconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 通用键值配置：每日登录经验这类低频可配项放这里，避免为每一项都建一张表。 */
@Entity
@Table(name = "app_config")
public class AppConfig {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 255)
    private String configValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppConfig() {
    }

    /** 新增一条配置（键不存在时用） */
    public static AppConfig of(String key, String value) {
        AppConfig config = new AppConfig();
        config.configKey = key;
        config.configValue = value;
        config.updatedAt = LocalDateTime.now();
        return config;
    }

    public void setValue(String value) {
        this.configValue = value;
        this.updatedAt = LocalDateTime.now();
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getConfigValue() {
        return configValue;
    }
}
