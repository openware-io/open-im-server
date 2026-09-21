package com.gvchat.im.user.domain.account.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SelfDestructPolicyTest {
  @Test
  void shouldParseKnownAndUnknownValues() {
    assertThat(SelfDestructPolicy.fromValue("1mo")).isEqualTo(SelfDestructPolicy.ONE_MONTH);
    assertThat(SelfDestructPolicy.fromValue("3MO")).isEqualTo(SelfDestructPolicy.THREE_MONTHS);
    assertThat(SelfDestructPolicy.fromValue("6mo")).isEqualTo(SelfDestructPolicy.SIX_MONTHS);
    assertThat(SelfDestructPolicy.fromValue("1yr")).isEqualTo(SelfDestructPolicy.ONE_YEAR);
    assertThat(SelfDestructPolicy.fromValue("off")).isEqualTo(SelfDestructPolicy.OFF);
    assertThat(SelfDestructPolicy.fromValue(null)).isEqualTo(SelfDestructPolicy.OFF);
    assertThat(SelfDestructPolicy.fromValue("bogus")).isEqualTo(SelfDestructPolicy.OFF);
  }

  @Test
  void offShouldBeDisabledWithNullDeadline() {
    assertThat(SelfDestructPolicy.OFF.isEnabled()).isFalse();
    assertThat(SelfDestructPolicy.OFF.deadlineAfter(LocalDateTime.of(2025, 1, 1, 0, 0))).isNull();
  }

  @Test
  void enabledPolicyShouldAddPeriodToActiveTime() {
    LocalDateTime base = LocalDateTime.of(2025, 1, 15, 10, 0);
    assertThat(SelfDestructPolicy.ONE_MONTH.isEnabled()).isTrue();
    assertThat(SelfDestructPolicy.ONE_MONTH.deadlineAfter(base))
        .isEqualTo(LocalDateTime.of(2025, 2, 15, 10, 0));
    assertThat(SelfDestructPolicy.THREE_MONTHS.deadlineAfter(base))
        .isEqualTo(LocalDateTime.of(2025, 4, 15, 10, 0));
    assertThat(SelfDestructPolicy.SIX_MONTHS.deadlineAfter(base))
        .isEqualTo(LocalDateTime.of(2025, 7, 15, 10, 0));
    assertThat(SelfDestructPolicy.ONE_YEAR.deadlineAfter(base))
        .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 0));
  }
}
