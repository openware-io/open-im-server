package com.gvchat.im.user.domain.account.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserAccountAuthenticationVersionTest {
  @Test
  void passwordChangeShouldAdvanceAuthenticationVersion() {
    UserAccount account = UserAccount.register(
        "member", "old-hash", "member", "", "", LocalDateTime.now());

    account.changePassword("new-hash", LocalDateTime.now());

    assertThat(account.getStatusVersion()).isEqualTo(2L);
  }
}
