package com.ljx.server.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class DatabaseDtos {

    /**
     * 当前生效的数据库配置与连接池状态。**密码永不返回**。
     * <p>
     * kind / kindHint / persistent 是给使用者看的"结论"：
     * 光看 jdbc:h2:file:./data/ljx 很难判断这是不是正式环境该用的库，
     * 所以后端直接把类型、风险与建议算好返回，前端只负责展示。
     */
    public record DatabaseStatusDto(
            String kind,
            String kindHint,
            boolean persistent,
            String jdbcUrl,
            String username,
            String driverClass,
            String productName,
            String productVersion,
            String profiles,
            String flywayVersion,
            int appliedMigrations,
            int poolActive,
            int poolIdle,
            int poolTotal,
            int poolMax,
            String poolState) {
    }

    /** 试连参数：只用于当场测试，不落库、不写文件、不改数据源 */
    public record DatabaseTestRequest(
            @NotBlank(message = "请填写主机地址") @Size(max = 255) String host,
            @Min(value = 1, message = "端口不合法") @Max(value = 65535, message = "端口不合法") Integer port,
            @NotBlank(message = "请填写数据库名") @Size(max = 64) String database,
            @NotBlank(message = "请填写用户名") @Size(max = 64) String username,
            String password) {
    }

    public record DatabaseTestResponse(boolean ok, String message, long elapsedMs, String jdbcUrl) {
    }

    /** 生成部署用的配置片段（由使用者自行粘贴到部署环境） */
    public record DatabaseSnippetResponse(String yaml, List<String> envVars, String note) {
    }
}
