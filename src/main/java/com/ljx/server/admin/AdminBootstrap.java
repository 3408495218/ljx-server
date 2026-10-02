package com.ljx.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 首个管理员初始化：**只在管理员表为空且配置了用户名/密码时**创建。
 * <p>
 * 刻意既不做"默认账号密码"，也不提供"首次访问引导创建"：
 * 后台一旦挂到公网，任何可自助初始化的入口都是提权风险。
 * 未配置时只打一行提示，玩家端功能完全不受影响。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserRepository adminUserRepository;
    private final AdminProperties props;
    /** 与 AuthService / EmailService 一致：项目里没有 PasswordEncoder Bean */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminBootstrap(AdminUserRepository adminUserRepository, AdminProperties props) {
        this.adminUserRepository = adminUserRepository;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUserRepository.count() > 0) {
            return;
        }
        String username = props.getUsername() == null ? "" : props.getUsername().trim();
        String password = props.getPassword() == null ? "" : props.getPassword();
        if (username.isEmpty() || password.isEmpty()) {
            log.warn("未创建管理员：请设置 LJX_ADMIN_USERNAME 与 LJX_ADMIN_PASSWORD 后重启（后台入口 /admin）");
            return;
        }
        adminUserRepository.save(new AdminUser(username, passwordEncoder.encode(password), LocalDateTime.now()));
        log.info("管理员已创建：{}（后台入口 /admin）", username);
    }
}
