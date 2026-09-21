package com.gvchat.im.user.domain.account.model;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.Arrays;

/**
 * 账号自毁策略：长期不登录自动硬删账号与数据。
 *
 * <p>登录（或注册）会刷新 {@code lastLoginAt} 并据此重新计算 {@code selfDestructAt}；
 * 到期后由清扫任务清理该用户的聊天记录（保留账号）并广播 {@code UserChatRecordsPurged} 事件。</p>
 */
public enum SelfDestructPolicy {
  OFF("off", null),
  ONE_MONTH("1mo", Period.ofMonths(1)),
  THREE_MONTHS("3mo", Period.ofMonths(3)),
  SIX_MONTHS("6mo", Period.ofMonths(6)),
  ONE_YEAR("1yr", Period.ofYears(1));

  private final String value;
  private final Period inactivityPeriod;

  SelfDestructPolicy(String value, Period inactivityPeriod) {
    this.value = value;
    this.inactivityPeriod = inactivityPeriod;
  }

  public String value() {
    return value;
  }

  public boolean isEnabled() {
    return inactivityPeriod != null;
  }

  /** 由「最近活跃时间」计算到期截止时间；关闭策略时返回 null。 */
  public LocalDateTime deadlineAfter(LocalDateTime lastActiveAt) {
    if (inactivityPeriod == null) {
      return null;
    }
    return lastActiveAt.plus(inactivityPeriod);
  }

  /** 解析策略值；未知/空白一律视为关闭。 */
  public static SelfDestructPolicy fromValue(String value) {
    if (value == null || value.isBlank()) {
      return OFF;
    }
    String normalized = value.trim().toLowerCase();
    return Arrays.stream(values())
        .filter(policy -> policy.value.equals(normalized))
        .findFirst()
        .orElse(OFF);
  }
}
