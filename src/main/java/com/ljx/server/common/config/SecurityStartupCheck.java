package com.ljx.server.common.config;

import com.ljx.server.auth.JwtProperties;
import com.ljx.server.auth.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 启动期安全自检。
 * <p>
 * 只在 {@code prod} profile 下**直接拒绝启动**，其余环境打醒目警告 —— 这样既不会误伤本地开发与
 * 集成测试（它们本来就用默认值），又保证生产环境不会带着"仓库里就有的密钥/临时目录"上线。
 * <p>
 * 检查项：
 * <ol>
 *   <li><b>JWT 默认密钥</b>：默认值写在仓库里，任何人都能用它伪造任意玩家的访问令牌；</li>
 *   <li><b>邮件通道未开启</b>：验证码会被打进应用日志；</li>
 *   <li><b>存储目录是系统临时目录</b>：Linux 下重启会清空，所有客户端压缩包丢失；</li>
 *   <li><b>数据库是内置 H2 内存库</b>：重启后全部业务数据丢失。</li>
 * </ol>
 */
@Component
public class SecurityStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);

    /** application.yml 里 jwt.secret 的默认值：出现它说明使用者没有覆盖 */
    private static final String DEV_JWT_SECRET =
            "bGp4LWRldi1qd3Qtc2VjcmV0LWtleS1kby1ub3QtdXNlLWluLXByb2R1Y3Rpb24tMjAyNg==";

    private final JwtProperties jwtProperties;
    private final MailProperties mailProperties;
    private final Environment environment;
    private final DataSource dataSource;
    private final String storageDir;

    public SecurityStartupCheck(JwtProperties jwtProperties,
                                MailProperties mailProperties,
                                Environment environment,
                                DataSource dataSource,
                                @org.springframework.beans.factory.annotation.Value("${ljx.storage.dir}") String storageDir) {
        this.jwtProperties = jwtProperties;
        this.mailProperties = mailProperties;
        this.environment = environment;
        this.dataSource = dataSource;
        this.storageDir = storageDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean production = environment.matchesProfiles("prod") || environment.matchesProfiles("production");

        checkJwtSecret(production);
        checkMailChannel(production);
        checkStorageDir(production);
        checkDataSource(production);

        if (!production) {
            log.info("启动自检完成（当前不是 prod profile，以下问题仅提示不拦截）");
        }
    }

    private void checkJwtSecret(boolean production) {
        if (!DEV_JWT_SECRET.equals(jwtProperties.secret())) {
            return;
        }
        String message = "仍在使用**内置的 JWT 默认密钥**。该密钥就写在代码仓库里，"
                + "任何人都能用它伪造任意玩家的登录令牌。请设置环境变量 LJX_JWT_SECRET"
                + "（任意足够长的随机串，base64 编码）后重启。";
        if (production) {
            throw new IllegalStateException("生产环境禁止启动：" + message);
        }
        log.warn("⚠️ 安全提示：{}", message);
    }

    private void checkMailChannel(boolean production) {
        if (mailProperties.isEnabled()) {
            return;
        }
        String message = "邮件通道未开启（ljx.mail.enabled=false），邮箱验证码会被打印到应用日志。"
                + "正式环境请开启并配置 SMTP（可在后台「邮件」面板里设置或通过环境变量注入）。";
        if (production) {
            throw new IllegalStateException("生产环境禁止启动：" + message);
        }
        log.warn("⚠️ 安全提示：{}", message);
    }

    private void checkStorageDir(boolean production) {
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null) {
            return;
        }
        try {
            String base = Paths.get(tmp).toAbsolutePath().normalize().toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT);
            Path actual = Paths.get(storageDir).toAbsolutePath().normalize();
            String actualText = actual.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!actualText.startsWith(base)) {
                return;
            }
            String message = "文件存储目录落在**系统临时目录**（" + actual
                    + "），服务器重启或系统清理会删掉全部客户端压缩包。"
                    + "请把环境变量 LJX_STORAGE_DIR 指向数据盘后重启。";
            if (production) {
                throw new IllegalStateException("生产环境禁止启动：" + message);
            }
            log.warn("⚠️ 安全提示：{}", message);
        } catch (RuntimeException e) {
            // 路径解析异常不影响启动
        }
    }

    private void checkDataSource(boolean production) {
        if (!(dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari)) {
            return;
        }
        String url = hikari.getJdbcUrl();
        if (url == null || !url.startsWith("jdbc:h2:mem")) {
            return;
        }
        String message = "当前数据库是**内置 H2 内存库**（" + url
                + "），后端一重启账号、房间、订单等全部数据都会丢失。"
                + "请激活 mysql profile 或把 spring.datasource.url 指向持久数据库。";
        if (production) {
            throw new IllegalStateException("生产环境禁止启动：" + message);
        }
        log.warn("⚠️ 安全提示：{}", message);
    }
}
