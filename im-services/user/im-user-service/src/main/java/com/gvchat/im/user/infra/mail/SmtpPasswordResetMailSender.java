package com.gvchat.im.user.infra.mail;

import com.gvchat.im.user.domain.account.port.PasswordResetMailSender;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * 密码重置邮件 SMTP 发送实现。
 *
 * <p>未启用（{@code im.mail.enabled=false}，默认）时不发信，只记录模板名与掩码邮箱；
 * 生产环境配置 SMTP 凭据后启用即可投递真实邮件。重置链接（含一次性 token）只出现在邮件正文里，
 * 任何情况下都不写日志。</p>
 */
@Component
@Slf4j
public class SmtpPasswordResetMailSender implements PasswordResetMailSender {
  @Value("${im.mail.enabled:false}")
  private boolean enabled;
  @Value("${im.mail.host:}")
  private String host;
  @Value("${im.mail.port:587}")
  private int port;
  @Value("${im.mail.username:}")
  private String username;
  @Value("${im.mail.password:}")
  private String password;
  @Value("${im.mail.from:}")
  private String from;

  @Override
  public void send(String email, String resetLink) {
    if (!enabled) {
      // 禁止把重置链接（含一次性 token）写进日志：日志可读即可接管账号，只记模板与掩码邮箱
      log.info("[password-reset] mail disabled, template=password-reset, maskedEmail={}", maskEmail(email));
      return;
    }
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(email);
      message.setSubject("重置密码");
      message.setText("请在 30 分钟内点击以下链接重置密码：" + resetLink);
      mailSender().send(message);
      log.info("[password-reset] sent reset email, template=password-reset, maskedEmail={}", maskEmail(email));
    } catch (RuntimeException ex) {
      log.error("Failed to send password reset email, template=password-reset, maskedEmail={}", maskEmail(email), ex);
      throw new IllegalStateException("Failed to send password reset email", ex);
    }
  }

  /** 邮箱脱敏：日志不落明文邮箱 PII。 */
  private static String maskEmail(String email) {
    if (email == null || email.isEmpty()) {
      return email;
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return email.charAt(0) + "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
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
}
