package com.ljx.server.appconfig;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppConfigRepository extends JpaRepository<AppConfig, String> {

    /** 取整数值配置；缺失或非法时返回默认值（配置项异常不该让业务挂掉） */
    default int intValue(String key, int fallback) {
        return findById(key)
                .map(AppConfig::getConfigValue)
                .map(raw -> {
                    try {
                        return Integer.parseInt(raw.trim());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}
