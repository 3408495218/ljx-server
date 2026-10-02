package com.ljx.server.common.audit;

import com.ljx.server.common.config.SecurityProperties;
import com.ljx.server.common.net.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计留痕入口。写入参与调用方事务：业务成功才留痕，
 * 失败路径（如登录失败）由调用方在抛出前显式记录。
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final SecurityProperties securityProperties;

    public AuditService(AuditLogRepository repository, SecurityProperties securityProperties) {
        this.repository = repository;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public void record(Long accountId, AuditAction action, String detail, boolean success) {
        repository.save(new AuditLog(accountId, clientIp(), action, detail, success));
    }

    /** 定时任务等无请求上下文场景返回 null */
    private String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return ClientIp.resolve(request, securityProperties.isTrustForwardedHeader());
    }
}