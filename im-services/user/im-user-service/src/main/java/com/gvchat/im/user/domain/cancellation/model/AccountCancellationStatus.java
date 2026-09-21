package com.gvchat.im.user.domain.cancellation.model;

import java.util.Arrays;

/** 账号注销申请状态。 */
public enum AccountCancellationStatus {
  PENDING,
  PROCESSING,
  COMPLETED,
  FAILED;

  public static AccountCancellationStatus fromValue(String value) {
    if (value == null || value.isBlank()) {
      return PENDING;
    }
    String normalized = value.trim().toLowerCase();
    return Arrays.stream(values())
        .filter(status -> status.name().toLowerCase().equals(normalized))
        .findFirst()
        .orElse(PENDING);
  }
}
