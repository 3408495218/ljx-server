package com.ljx.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 邮件发送入口（{@code @Primary}）：按**当前生效的 SMTP 配置**决定走真实发送还是记日志。
 * <p>
 * 把"配了什么"与"怎么发"分开，好处是<b>后台改完 SMTP 配置立刻生效</b>：
 * 每次发送都重新解析配置，不需要重启；没配置时自动回落到日志通道（验证码仍可用，便于本地跑通流程）。
 */
@Component
@Primary
public class MailSenderRouter implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(MailSenderRouter.class);

    private final MailConfigService mailConfigService;
    private final SmtpMailSender smtpMailSender;
    private final LogMailSender logMailSender;

    public MailSenderRouter(MailConfigService mailConfigService,
                            SmtpMailSender smtpMailSender,
                            LogMailSender logMailSender) {
        this.mailConfigService = mailConfigService;
        this.smtpMailSender = smtpMailSender;
        this.logMailSender = logMailSender;
    }

    @Override
    public void send(String email, String subject, String code) {
        var settings = mailConfigService.current();
        if (settings.isEmpty()) {
            log.debug("未配置 SMTP，验证码走日志通道");
            logMailSender.send(email, subject, code);
            return;
        }
        smtpMailSender.send(settings.get(), email, subject, code);
    }
}
