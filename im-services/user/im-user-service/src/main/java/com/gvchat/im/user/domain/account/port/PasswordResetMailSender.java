package com.gvchat.im.user.domain.account.port;

/** 密码重置邮件发送端口：向用户邮箱发送含重置链接的邮件（不含明文/新密码）。 */
public interface PasswordResetMailSender {
  void send(String email, String resetLink);
}
