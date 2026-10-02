package com.ljx.server.admin;

import com.ljx.server.admin.dto.DatabaseDtos.DatabaseSnippetResponse;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseStatusDto;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseTestRequest;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseTestResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * 数据库面板的后端：**只读现状 + 当场试连 + 生成配置片段**。
 * <p>
 * 刻意**不做"运行时改数据源"**，也**不把配置写进文件**，原因有三：
 * <ol>
 *   <li>Spring 的 DataSource 在启动时固化，热切换会让连接池、JPA 元模型、Flyway 全部失配；</li>
 *   <li>连接串写错 → 下次启动连不上 → 连后台都进不去，只能手工改文件（自锁）；</li>
 *   <li>连接串里的密码属于**部署凭据**，不该落进业务库或仓库。</li>
 * </ol>
 * 所以这里只降低"试错成本"：先把连接试通，再把片段交给使用者在部署环境里设置。
 */
@Service
public class DatabaseAdminService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAdminService.class);

    /** 试连超时：不能让后台请求被一个连不通的地址挂住 */
    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    private static final String DEFAULT_MYSQL_PORT = "3306";

    private final DataSource dataSource;
    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseAdminService(DataSource dataSource, Environment environment,
                                JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 当前生效的配置（**密码不返回**）与连接池状态 */
    public DatabaseStatusDto status() {
        String jdbcUrl = null;
        String username = null;
        String driverClass = null;
        int active = 0;
        int idle = 0;
        int total = 0;
        int max = 0;
        String poolState;

        if (dataSource instanceof HikariDataSource hikari) {
            jdbcUrl = hikari.getJdbcUrl();
            username = hikari.getUsername();
            driverClass = hikari.getDriverClassName();
            max = hikari.getMaximumPoolSize();
            poolState = hikari.isClosed() ? "已关闭" : "运行中";
            try {
                var pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    active = pool.getActiveConnections();
                    idle = pool.getIdleConnections();
                    total = pool.getTotalConnections();
                    if (pool.getThreadsAwaitingConnection() > 0) {
                        poolState = "繁忙（有请求在等连接）";
                    }
                }
            } catch (RuntimeException e) {
                // 池尚未初始化时会抛异常，不影响状态展示
            }
        } else {
            poolState = dataSource.getClass().getSimpleName();
        }

        String productName = null;
        String productVersion = null;
        try (Connection connection = dataSource.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
            productVersion = connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            poolState = "取连接失败：" + e.getMessage();
        }

        String flywayVersion = null;
        int applied = 0;
        try {
            flywayVersion = jdbcTemplate.query(
                    "select version from flyway_schema_history where success = true "
                            + "order by installed_rank desc limit 1",
                    rs -> rs.next() ? rs.getString(1) : null);
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from flyway_schema_history", Integer.class);
            applied = count == null ? 0 : count;
        } catch (RuntimeException e) {
            // 未启用 Flyway 或表不存在：保持空值
            flywayVersion = null;
        }

        String kind = detectKind(jdbcUrl, productName);
        return new DatabaseStatusDto(kind, kindHint(kind, jdbcUrl), isPersistent(jdbcUrl),
                jdbcUrl, username, driverClass, productName, productVersion,
                String.join(",", environment.getActiveProfiles()), flywayVersion, applied,
                active, idle, total, max, poolState);
    }

    /**
     * 当场试连：临时新建一条连接跑 {@code SELECT 1}。
     * 不写文件、不改数据源、不落库；密码只在本请求内存里用一次，也不写进日志。
     */
    public DatabaseTestResponse test(DatabaseTestRequest request) {
        String jdbcUrl = buildJdbcUrl(request.host(), request.port(), request.database());
        long startedAt = System.currentTimeMillis();
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl, request.username(), request.password() == null ? "" : request.password());
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select 1")) {
                boolean ok = resultSet.next();
                long elapsed = System.currentTimeMillis() - startedAt;
                log.info("数据库试连成功：{}（{} ms）", safeUrl(jdbcUrl), elapsed);
                return new DatabaseTestResponse(true,
                        ok ? "连接成功，可以正常读写" : "已连上，但测试语句未返回结果", elapsed, jdbcUrl);
            }
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            String reason = classify(e);
            log.warn("数据库试连失败：{} → {}", safeUrl(jdbcUrl), reason);
            return new DatabaseTestResponse(false, reason, elapsed, jdbcUrl);
        }
    }

    /** 生成部署用的配置片段；密码一律用占位符，由使用者在环境变量里填 */
    public DatabaseSnippetResponse snippet(DatabaseTestRequest request) {
        String jdbcUrl = buildJdbcUrl(request.host(), request.port(), request.database());
        String nl = System.lineSeparator();
        String yaml = String.join(nl,
                "# 追加到 application-mysql.yml（或对应 profile 文件）",
                "spring:",
                "  datasource:",
                "    url: ${LJX_DB_URL}",
                "    username: ${LJX_DB_USER}",
                "    password: ${LJX_DB_PASSWORD}",
                "    driver-class-name: com.mysql.cj.jdbc.Driver",
                "",
                "# 部署环境设置以下环境变量（**不要提交到仓库**）",
                "# LJX_DB_URL=" + jdbcUrl,
                "# LJX_DB_USER=" + request.username(),
                "# LJX_DB_PASSWORD=<在此填入密码>");
        List<String> envVars = List.of(
                "LJX_DB_URL=" + jdbcUrl,
                "LJX_DB_USER=" + request.username(),
                "LJX_DB_PASSWORD=<在此填入密码>");
        return new DatabaseSnippetResponse(yaml, envVars,
                "本面板不会修改任何配置文件、也不会切换运行时数据源；改完配置需**重启后端**才生效。");
    }

    /** 判断当前实际在用什么数据库（前端据此给出结论与建议） */
    private String detectKind(String jdbcUrl, String productName) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        String product = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:mysql") || product.contains("mysql") || product.contains("mariadb")) {
            return "MySQL";
        }
        if (url.startsWith("jdbc:h2") || product.contains("h2")) {
            return "H2";
        }
        if (url.startsWith("jdbc:postgresql") || product.contains("postgres")) {
            return "PostgreSQL";
        }
        return productName == null || productName.isBlank() ? "未知" : productName;
    }

    /** H2 内存库重启即清空；H2 文件库与 MySQL 才是持久的 */
    private boolean isPersistent(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:h2")) {
            return !url.contains(":mem:");
        }
        return url.startsWith("jdbc:mysql") || url.startsWith("jdbc:postgresql");
    }

    /** 把"这是什么库、能不能用于正式环境"直接说清楚 */
    private String kindHint(String kind, String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "H2" -> url.contains(":mem:")
                    ? "内置 H2 内存库：数据只存在内存里，重启后全部丢失！仅适合临时测试，"
                    + "正式开服请切换到 MySQL。"
                    : "内置 H2 文件库：数据保存在后端目录下的 data/ 里，重启不丢，"
                    + "适合本地测试与单机使用；要正式对外开服建议切换到 MySQL。";
            case "MySQL" -> "已连接 MySQL，是正式使用推荐的方式。";
            case "PostgreSQL" -> "已连接 PostgreSQL（本平台默认脚本按 MySQL 编写，请自行确认兼容性）。";
            default -> "未能识别数据库类型，请核对 spring.datasource.url 配置。";
        };
    }

    /** 拼 MySQL 连接串（带常用中文参数） */
    private String buildJdbcUrl(String host, Integer port, String database) {
        String actualPort = port == null ? DEFAULT_MYSQL_PORT : String.valueOf(port);
        return "jdbc:mysql://" + host + ":" + actualPort + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    /** 把 SQLException 归成使用者能直接照着改的几类原因 */
    private String classify(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);

        if (state.startsWith("28") || message.contains("access denied")
                || message.contains("authentication")) {
            return "用户名或密码错误";
        }
        if (message.contains("unknown database")) {
            return "数据库不存在（请先在 MySQL 里创建这个库）";
        }
        if (message.contains("communications link failure") || message.contains("connection refused")
                || message.contains("connect timed out") || message.contains("timeout")) {
            return "无法连接（请检查主机、端口、防火墙，以及 MySQL 是否允许远程连接）";
        }
        if (message.contains("no suitable driver")) {
            return "缺少 MySQL 驱动（确认依赖里有 mysql-connector-j）";
        }
        return "连接失败：" + e.getMessage();
    }

    /** 日志里的连接串去掉查询参数 */
    private String safeUrl(String jdbcUrl) {
        int index = jdbcUrl.indexOf('?');
        return index < 0 ? jdbcUrl : jdbcUrl.substring(0, index);
    }
}
