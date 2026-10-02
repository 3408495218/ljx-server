package com.ljx.server.common.config;

import com.ljx.server.admin.AdminAuthInterceptor;
import com.ljx.server.admin.AdminIpWhitelistInterceptor;
import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    /** 管理端 IP 白名单：独立拦截器，覆盖含登录在内的全部 /api/admin/** */
    private final AdminIpWhitelistInterceptor adminIpWhitelistInterceptor;

    public WebConfig(AuthInterceptor authInterceptor,
                     RateLimitInterceptor rateLimitInterceptor,
                     AdminAuthInterceptor adminAuthInterceptor,
                     AdminIpWhitelistInterceptor adminIpWhitelistInterceptor) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.adminIpWhitelistInterceptor = adminIpWhitelistInterceptor;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 限流先于认证：未认证的洪水请求不应进入 JWT 解析
        // 挂到全部 /api/**：命中规则才限流，读接口与未登记的接口直接放行（见 ruleFor）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
        // IP 白名单必须**排在所有管理端拦截器之前**，且**不排除登录接口** ——
        // 否则白名单外的 IP 仍能尝试登录，等于白名单只锁了一半。
        registry.addInterceptor(adminIpWhitelistInterceptor)
                .addPathPatterns("/api/admin/**");
        // 管理端独立认证：放行只精确到登录接口（不要用 /auth/** 通配，那会把 logout 一起放行）
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/auth/login");
        // 玩家认证必须把 /api/admin/** 排除掉，否则它会先用玩家令牌的规则挡下管理请求
        // （两套令牌互不通用，这是本轮很容易踩的坑）
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // /api/announcements 是公开只读接口：未登录用户也要能看到公告
                .excludePathPatterns("/api/auth/**", "/api/admin/**", "/api/announcements");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "tauri://localhost",
                        "http://tauri.localhost",
                        "http://localhost:5173")
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}