package com.ljx.server.auth;

import com.ljx.server.auth.MailConfigService.SmtpSettings;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 真实 SMTP 通道。
 * <p>
 * **每次发送时按"当前生效配置"临时构建 {@link JavaMailSenderImpl}**，而不是注入一个启动期创建的
 * `JavaMailSender` bean —— 后者在应用启动时就固定了 host/账号/密码，
 * 后台改了 SMTP 配置必须重启才生效（这正是我们要避免的）。
 * 邮件是低频操作，每次建连接的开销可以接受。
 */
@Component
public class SmtpMailSender {

    private static final String SENDER_NAME = "垃圾侠";

    /** 用给定的配置发一封验证码邮件；失败以 MailException 上抛，由调用方转业务错误码 */
    public void send(SmtpSettings settings, String email, String subject, String code) {
        JavaMailSenderImpl sender = build(settings);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(settings.from(), SENDER_NAME);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body(code), false);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("构造验证码邮件失败：" + e.getMessage(), e);
        }
    }

    private JavaMailSenderImpl build(SmtpSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());
        sender.setDefaultEncoding("UTF-8");

        Properties mail = sender.getJavaMailProperties();
        mail.put("mail.transport.protocol", "smtp");
        mail.put("mail.smtp.auth", "true");
        // QQ 邮箱 465 是隐式 SSL；改用 587 时把「SSL」关掉，走 STARTTLS
        mail.put("mail.smtp.ssl.enable", String.valueOf(settings.sslEnabled()));
        mail.put("mail.smtp.starttls.enable", String.valueOf(!settings.sslEnabled()));
        mail.put("mail.smtp.connectiontimeout", "10000");
        mail.put("mail.smtp.timeout", "10000");
        return sender;
    }

    private static String body(String code) {
        return """
                %s，您的垃圾侠验证码是：%s

                验证码 5 分钟内有效，请勿转发给他人。如非本人操作，请忽略本邮件。
                """.formatted(SENDER_NAME, code);
    }
}
