package io.openware.im.user.domain.account.port;

/** 密码重置短信发送端口：向手机号发送含验证码的短信（不含明文密码）。 */
public interface PasswordResetSmsSender {
  void sendVerificationCode(String phone, String code);
}
