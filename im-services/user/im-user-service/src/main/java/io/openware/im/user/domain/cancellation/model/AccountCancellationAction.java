package io.openware.im.user.domain.cancellation.model;

import java.util.Arrays;

/** 账号注销审计日志动作。 */
public enum AccountCancellationAction {
  REQUESTED,
  PROCESSING,
  COMPLETED,
  FAILED;

  public static AccountCancellationAction fromValue(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toLowerCase();
    return Arrays.stream(values())
        .filter(action -> action.name().toLowerCase().equals(normalized))
        .findFirst()
        .orElse(null);
  }
}
