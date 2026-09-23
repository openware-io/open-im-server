package io.openware.common.mail.infra.provider;

import java.util.Properties;
import io.openware.common.mail.domain.RecipientPrivacy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * SMTP 邮件发送实现。
 *
 * <p>配置占位（{@code mail.smtp.*} 默认为空）时 {@link #enabled()} 返回 false，
 * {@link #send} 仅记录日志、不真正发信、不抛异常，保证服务在未配置 SMTP 凭据时也能正常启动运行；
 * 配置 host/username/from 后启用真实投递（用户名/密码鉴权 + STARTTLS）。</p>
 */
@Component
@Slf4j
public class SmtpMailProvider implements MailProvider {

    @Value("${mail.smtp.host:}")
    private String host;
    @Value("${mail.smtp.port:587}")
    private int port;
    @Value("${mail.smtp.username:}")
    private String username;
    @Value("${mail.smtp.password:}")
    private String password;
    @Value("${mail.smtp.from:}")
    private String from;

    @Override
    public String provider() {
        return "smtp";
    }

    @Override
    public boolean enabled() {
        return isConfigured(host) && isConfigured(username) && isConfigured(from);
    }

    @Override
    public void send(String from, String to, String subject, String content) {
        if (!enabled()) {
            log.info("[mail] smtp not configured (placeholder), would send to={} subject={}",
                RecipientPrivacy.mask(to), subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(isConfigured(this.from) ? this.from : from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
            mailSender().send(message);
            log.info("[mail] smtp sent to={} subject={}", RecipientPrivacy.mask(to), subject);
        } catch (RuntimeException ex) {
            log.error("[mail] failed to send to={}", RecipientPrivacy.mask(to), ex);
            throw new IllegalStateException("Failed to send mail", ex);
        }
    }

    private JavaMailSender mailSender() {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        impl.setUsername(username);
        impl.setPassword(password);
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        return impl;
    }

    private boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }
}
