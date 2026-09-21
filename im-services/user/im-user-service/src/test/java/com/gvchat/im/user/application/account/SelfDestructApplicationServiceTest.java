package com.gvchat.im.user.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.im.user.application.account.result.SelfDestructPolicyResult;
import com.gvchat.im.user.domain.account.event.UserChatRecordsPurged;
import com.gvchat.im.user.domain.account.model.SelfDestructPolicy;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountRole;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.port.UserStatusEventOutbox;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelfDestructApplicationServiceTest {
  private final UserAccountRepository accountRepository = mock(UserAccountRepository.class);
  private final UserStatusEventOutbox eventOutbox = mock(UserStatusEventOutbox.class);
  private final SelfDestructApplicationService service =
      new SelfDestructApplicationService(accountRepository, eventOutbox);

  @Test
  void setPolicyShouldComputeDeadlineFromLastLoginAndPersist() {
    LocalDateTime lastLogin = LocalDateTime.of(2025, 1, 10, 8, 0);
    UserAccount account = account(7L, SelfDestructPolicy.OFF, null, lastLogin);
    when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
    when(accountRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));

    SelfDestructPolicyResult result = service.setPolicy(7L, "1mo");

    assertThat(result.policy()).isEqualTo("1mo");
    assertThat(result.selfDestructAt()).isEqualTo(lastLogin.plusMonths(1));
    verify(accountRepository).save(account);
  }

  @Test
  void setPolicyOffShouldClearDeadline() {
    LocalDateTime lastLogin = LocalDateTime.of(2025, 1, 10, 8, 0);
    UserAccount account = account(7L, SelfDestructPolicy.ONE_MONTH, lastLogin.plusMonths(1), lastLogin);
    when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
    when(accountRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));

    SelfDestructPolicyResult result = service.setPolicy(7L, "off");

    assertThat(result.policy()).isEqualTo("off");
    assertThat(result.selfDestructAt()).isNull();
  }

  @Test
  void setPolicyWithUnknownValueShouldReject() {
    UserAccount account = account(7L, SelfDestructPolicy.OFF, null, LocalDateTime.now());
    when(accountRepository.findById(7L)).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service.setPolicy(7L, "bogus"))
        .isInstanceOf(ApiException.class);
    verify(accountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void sweepShouldResetPolicyKeepAccountAndPublishPurgeEvents() {
    UserAccount first = account(1L, SelfDestructPolicy.ONE_MONTH, LocalDateTime.now().minusDays(1), LocalDateTime.now());
    UserAccount second = account(2L, SelfDestructPolicy.SIX_MONTHS, LocalDateTime.now().minusDays(2), LocalDateTime.now());
    when(accountRepository.findSelfDestructDue(any(LocalDateTime.class), any(Integer.class)))
        .thenReturn(List.of(first, second));

    int purged = service.sweepExpired(LocalDateTime.now(), 200);

    assertThat(purged).isEqualTo(2);
    verify(accountRepository, never()).hardDelete(any(Long.class));
    verify(accountRepository, times(2)).save(any(UserAccount.class));
    assertThat(first.getSelfDestructPolicy()).isEqualTo(SelfDestructPolicy.OFF);
    assertThat(first.getSelfDestructAt()).isNull();
    verify(eventOutbox, times(2)).append(any(UserChatRecordsPurged.class));
  }

  private static UserAccount account(Long id, SelfDestructPolicy policy, LocalDateTime selfDestructAt,
      LocalDateTime lastLoginAt) {
    UserAccount account = new UserAccount();
    account.restore(id, "user" + id, "user" + id, "", "hash", null, null, "", UserAccountStatus.ACTIVE, 1L,
        policy, selfDestructAt, lastLoginAt, UserAccountRole.USER, 0L, LocalDateTime.now(), 0L, LocalDateTime.now());
    return account;
  }
}
