package com.ljx.server.admin;

import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端鉴权：只拦 {@code /api/admin/**}（登录接口除外）。
 * <p>
 * 与玩家端 {@code AuthInterceptor} 是**两套独立认证**：本拦截器只认 {@code typ=admin} 的令牌，
 * 玩家令牌拿来会被 {@link AdminJwtService#parseAdmin} 拒绝——这是隔离的关键，有测试覆盖。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** 通过鉴权后，控制器用 @RequestAttribute(ATTR_ADMIN_ID) 取管理员 id */
    public static final String ATTR_ADMIN_ID = "adminId";

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 「必须改密码」状态下**仍然放行**的接口。
     * <p>
     * - `/api/admin/password`：改密码本身，否则管理员会被永久锁在门外；
     * - `/api/admin/me`：**前端正是靠它读 mustChangePassword 来决定是否弹强制窗口** ——
     *   若把它也拦掉，客户端拿不到标记，就变成"每个请求都 1604 但没有可用的提示"。
     *   它是只读接口，放行无安全影响。
     */
    private static final java.util.Set<String> ALLOWED_WHEN_PASSWORD_STALE =
            java.util.Set.of("/api/admin/password", "/api/admin/me");

    private final AdminJwtService jwtService;
    private final AdminProperties props;
    private final AdminUserRepository adminUserRepository;

    public AdminAuthInterceptor(AdminJwtService jwtService, AdminProperties props,
                                AdminUserRepository adminUserRepository) {
        this.jwtService = jwtService;
        this.props = props;
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // IP 白名单已拆到 AdminIpWhitelistInterceptor（它覆盖含登录在内的全部管理接口）
        String token = resolveToken(request);
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            Claims claims = jwtService.parseAdmin(token);
            Long adminId = Long.valueOf(claims.getSubject());
            request.setAttribute(ATTR_ADMIN_ID, adminId);

            // 强制改密码：首次创建 / 被重置的账号，改密码之前不允许调用任何其它管理接口。
            // 只放行"修改密码"本身，否则管理员会被永久锁在门外。
            if (!ALLOWED_WHEN_PASSWORD_STALE.contains(request.getRequestURI())) {
                boolean mustChange = adminUserRepository.findById(adminId)
                        .map(AdminUser::isMustChangePassword)
                        .orElse(false);
                if (mustChange) {
                    throw new BizException(ErrorCode.ADMIN_PASSWORD_CHANGE_REQUIRED);
                }
            }
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 过期 / 签名不符 / 非管理端令牌一律按"登录状态已失效"处理
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
