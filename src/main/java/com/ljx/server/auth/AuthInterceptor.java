package com.ljx.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.api.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** /api/** 统一 Bearer 认证；通过后把 accountId 放入 request attribute */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_ACCOUNT_ID = "accountId";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    /**
     * 「未登录也能访问」的只读接口：国服大厅的房间列表与详情。
     * <p>
     * 本软件定位是开服器，看大厅不该被登录挡住。这里**按方法 + 路径精确匹配**，
     * 而不是在 WebConfig 里用 excludePathPatterns —— 后者只能按路径排除，
     * 会把 `POST /api/rooms`（建房）也一起放行，建房是需要房主身份的。
     * <p>
     * 公开接口会**尽力解析令牌**：带了合法令牌就设上身份（收藏态、「我的房间」等要用到），
     * 没带、或令牌无效/过期则按匿名放行 —— 绝不能因为令牌问题把"看大厅"挡住。
     */
    private static boolean isPublicRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return "/api/rooms".equals(uri) || uri.matches("/api/rooms/\\d+");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 公开只读接口：放行。但**先尽力解析令牌** ——
        // 之前这里直接 return true，导致"带了令牌也当匿名"，收藏态与「我的房间」全部失效。
        if (isPublicRead(request)) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims = jwtService.parse(header.substring(7));
                    if (JwtService.TYPE_ACCESS.equals(claims.get("typ", String.class))) {
                        request.setAttribute(ATTR_ACCOUNT_ID, Long.valueOf(claims.getSubject()));
                    }
                } catch (JwtException | IllegalArgumentException ignored) {
                    // 令牌无效/过期时按匿名处理：公开接口不该因此失败
                }
            }
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response);
        }
        try {
            Claims claims = jwtService.parse(header.substring(7));
            if (!JwtService.TYPE_ACCESS.equals(claims.get("typ", String.class))) {
                return reject(response);
            }
            request.setAttribute(ATTR_ACCOUNT_ID, Long.valueOf(claims.getSubject()));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response);
        }
    }

    private boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.UNAUTHENTICATED.code(), ErrorCode.UNAUTHENTICATED.message())));
        return false;
    }
}
