package com.gvchat.im.user.domain.cancellation.model;

import java.util.Arrays;

/**
 * 账号注销的删除步骤（用于向用户提示删除项，枚举声明顺序即展示顺序）。
 *
 * <p>各端 App 数据与缓存的「标记删除」由 {@code UserDataWipeRequested} 事件广播实现。</p>
 */
public enum AccountCancellationStep {
  ACCOUNT("account"),
  CHAT_RECORDS("chat_records"),
  DEVICE_TOKENS("device_tokens"),
  FRIENDS("friends"),
  PROFILE_SETTINGS("profile_settings"),
  CLIENT_DATA("client_data");

  private final String code;

  AccountCancellationStep(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static AccountCancellationStep fromCode(String code) {
    if (code == null) {
      return null;
    }
    String normalized = code.trim().toLowerCase();
    return Arrays.stream(values())
        .filter(step -> step.code.equals(normalized))
        .findFirst()
        .orElse(null);
  }
}
