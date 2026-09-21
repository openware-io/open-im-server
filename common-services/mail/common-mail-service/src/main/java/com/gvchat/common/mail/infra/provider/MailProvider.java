package com.gvchat.common.mail.infra.provider;

/** 邮件服务商 SPI：SMTP 等。当前为骨架，真实发送由各实现接入。 */
public interface MailProvider {
    String provider();
    boolean enabled();
    void send(String from, String to, String subject, String content);
}
