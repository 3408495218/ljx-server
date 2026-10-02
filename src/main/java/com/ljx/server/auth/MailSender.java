package com.ljx.server.auth;

/** 邮件通道抽象：{@link SmtpMailSender} 走真实 SMTP，未开启时回落到 {@link LogMailSender} */
public interface MailSender {

    void send(String email, String subject, String code);
}
