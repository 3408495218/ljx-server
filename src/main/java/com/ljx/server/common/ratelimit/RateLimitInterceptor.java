package com.ljx.server.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.config.SecurityProperties;
import com.ljx.server.common.net.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证相关接口的 IP 限流。注册在 {@code AuthInterceptor} 之前，
 * 使未通过认证的洪水请求先被挡下，不必走 JWT 解析。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimiter rateLimiter,
                                RateLimitProperties properties,
                                SecurityProperties securityProperties,
                                ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !properties.isEnabled()) {
            return true;
        }
        RateLimitProperties.Rule rule = ruleFor(request.getRequestURI(), request.getMethod());
        log.debug("rate limit check uri={} method={} rule={}", request.getRequestURI(),
                request.getMethod(), rule == null ? "无（放行）" : rule.limit() + "次/" + rule.window());
        if (rule == null || rule.limit() <= 0) {
            return true;
        }
        String ip = ClientIp.resolve(request, securityProperties.isTrustForwardedHeader());
        if (rateLimiter.tryAcquire(request.getRequestURI() + "|" + ip, rule.limit(), rule.window())) {
            return true;
        }
        return reject(response);
    }

    /**
     * 命中哪条限流规则。
     * <p>
     * 认证类接口用精确匹配；业务写接口的 URI 里带房间号/商品号，只能按前缀匹配，
     * 并且**必须带上方法**——{@code POST /api/rooms}（建房）要限流，而 {@code GET /api/rooms}（看列表）不能限流。
     * 没有命中的接口直接放行（读接口全部不限流）。
     */
    private RateLimitProperties.Rule ruleFor(String uri, String method) {
        switch (uri) {
            case "/api/auth/login":
                return properties.getLogin();
            case "/api/auth/register":
                return properties.getRegister();
            case "/api/auth/refresh":
                return properties.getRefresh();
            case "/api/auth/guest":
                // 访客注册会很频繁（每个未登录客户端一次），但也不能完全放开：
                // 复用 register 的阈值（5 次/10 分钟）对正常使用足够，同时挡住脚本刷账号
                return properties.getRegister();
            case "/api/me/email/code":
                return properties.getEmailCode();
            default:
                break;
        }

        boolean write = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
        if (!write) {
            return null;
        }
        if (uri.equals("/api/rooms")) {
            return properties.getRoomWrite();                       // 建房
        }
        if (uri.matches("/api/rooms/\\d+/(presence|join|heartbeat)")) {
            return properties.getRoomWrite();                       // 进房/心跳
        }
        if (uri.matches("/api/rooms/\\d+/client-package")) {
            return properties.getRoomWrite();                       // 上传客户端包
        }
        if (uri.equals("/api/commerce/purchase") || uri.equals("/api/commerce/vip/purchase")) {
            return properties.getPurchase();                        // 购买
        }
        if (uri.equals("/api/commerce/redeem")) {
            return properties.getRedeem();                          // CDK 兑换
        }
        return null;
    }

    private boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.RATE_LIMITED.code(), ErrorCode.RATE_LIMITED.message())));
        return false;
    }
}