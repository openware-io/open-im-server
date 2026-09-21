package com.gvchat.im.user.domain.device.model;

import java.util.Arrays;

/** 设备会话状态：active（在线）/ kicked（被踢下线）/ logout（主动退出）。 */
public enum DeviceSessionStatus {
  ACTIVE("active"),
  KICKED("kicked"),
  LOGOUT("logout");

  private final String value;

  DeviceSessionStatus(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static DeviceSessionStatus fromValue(String value) {
    if (value == null) {
      return ACTIVE;
    }
    String normalized = value.trim().toLowerCase();
    return Arrays.stream(values())
        .filter(status -> status.value.equals(normalized))
        .findFirst()
        .orElse(ACTIVE);
  }
}
