package com.ljx.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 兜底通道：**没有可用的 SMTP 配置时**把验证码打到日志（由 {@link MailSenderRouter} 决定是否使用） */
@Component
public class LogMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LogMailSender.class);

    @Override
    public void send(String email, String subject, String code) {
        log.info("[MAIL][dev] to={} subject={} code={}", email, subject, code);
    }
}