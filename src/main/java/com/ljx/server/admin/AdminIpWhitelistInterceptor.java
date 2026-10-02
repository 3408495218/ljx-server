package com.ljx.server.admin;

import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.common.net.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端 IP 白名单。
 * <p>
 * 为什么**独立成拦截器**、而不是塞在 {@link AdminAuthInterceptor} 里：
 * 后者为了能在"还没登录"时放行登录请求，把 {@code /api/admin/auth/login} 从路径里排除了 ——
 * 于是白名单也跟着漏掉登录接口，**白名单外的 IP 照样能尝试登录**。
 * 而"锁内网"的本意是"外面根本连不上"，登录口也必须一起锁。
 * <p>
 * 这里挂到 {@code /api/admin/**} 且**不排除任何路径**，注册顺序在所有拦截器之前。
 * 白名单留空表示不限制（本地开发默认如此）。
 */
@Component
public class AdminIpWhitelistInterceptor implements HandlerInterceptor {

    private final AdminProperties props;

    public AdminIpWhitelistInterceptor(AdminProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (props.getAllowedIps().isEmpty()) {
            return true;
        }
        // 白名单比对用"客户端直连 IP"：此时还没到反代信任开关的语境，
        // 传 false 与限流保持一致口径（都由 ClientIp 统一解析）。
        String ip = ClientIp.resolve(request, false);
        if (ip == null || !props.getAllowedIps().contains(ip)) {
            throw new BizException(ErrorCode.ADMIN_FORBIDDEN);
        }
        return true;
    }
}
