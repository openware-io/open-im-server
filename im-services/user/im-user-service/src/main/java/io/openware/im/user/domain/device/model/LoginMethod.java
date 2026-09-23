package io.openware.im.user.domain.device.model;

import java.util.Arrays;

/**
 * 设备登录方式（用于多端登录统计与展示，参考百度网盘登录账号管理）。
 *
 * <p>VERIFICATION_CODE（验证码登录）当前尚未开放，属预留取值；其余取值对应当前实际登录入口。</p>
 */
public enum LoginMethod {
  PASSWORD("password"),
  VERIFICATION_CODE("verification_code"),
  QR_CODE("qr_code"),
  SSO("sso");

  private final String value;

  LoginMethod(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static LoginMethod fromValue(String value) {
    if (value == null) {
      return PASSWORD;
    }
    String normalized = value.trim().toLowerCase();
    return Arrays.stream(values())
        .filter(method -> method.value.equals(normalized))
        .findFirst()
        .orElse(PASSWORD);
  }
}
