package com.gvchat.im.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gvchat.common.enums.AppPlatform;
import org.junit.jupiter.api.Test;

class AppPlatformRequestConverterTest {
  private final AppPlatformRequestConverter converter = new AppPlatformRequestConverter();

  @Test
  void convertsThePublicLowercasePlatformValue() {
    assertThat(converter.convert("android")).isEqualTo(AppPlatform.ANDROID);
  }

  @Test
  void rejectsAnUnknownPlatform() {
    assertThatThrownBy(() -> converter.convert("unknown"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown AppPlatform: unknown");
  }
}
