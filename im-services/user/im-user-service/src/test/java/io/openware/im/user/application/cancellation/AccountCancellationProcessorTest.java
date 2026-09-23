package io.openware.im.user.application.cancellation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.event.UserChatRecordsPurged;
import io.openware.im.user.domain.account.event.UserDataWipeRequested;
import io.openware.im.user.domain.account.model.SelfDestructPolicy;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.port.PasswordHasher;
import io.openware.im.user.domain.account.port.UserAccountDataPurger;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.cancellation.model.AccountCancellation;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStatus;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationLogRepository;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountCancellationProcessorTest {
  private final AccountCancellationRepository cancellationRepository = mock(AccountCancellationRepository.class);
  private final AccountCancellationLogRepository logRepository = mock(AccountCancellationLogRepository.class);
  private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
  private final UserAccountDataPurger userAccountDataPurger = mock(UserAccountDataPurger.class);
  private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
  private final UserStatusEventOutbox outbox = mock(UserStatusEventOutbox.class);
  private final AccountCancellationProcessor processor = new AccountCancellationProcessor(cancellationRepository,
      logRepository, userAccountRepository, userAccountDataPurger, passwordHasher, outbox);

  @Test
  void shouldTombstoneAccountInsteadOfHardDeleteAndBroadcastEvents() {
    AccountCancellation application = AccountCancellation.request(10L, "user10", "昵称", "13800000000", "a@b.com",
        "token-hash", "web", "1.2.3.4", LocalDateTime.now());
    when(cancellationRepository.findById(99L)).thenReturn(Optional.of(application));
    UserAccount account = account(10L);
    when(userAccountRepository.findById(10L)).thenReturn(Optional.of(account));
    when(passwordHasher.hash(any())).thenReturn("random-hash");

    processor.processOne(99L);

    verify(userAccountDataPurger).purge(10L);
    verify(userAccountRepository, never()).hardDelete(10L);
    verify(userAccountRepository).save(account);
    assertEquals(UserAccountStatus.DISABLED, account.getStatus());
    assertEquals("已注销用户", account.getNickname());
    assertEquals(2L, account.getStatusVersion());
    verify(outbox).append(any(UserChatRecordsPurged.class));
    verify(outbox).append(any(UserAuthenticationInvalidated.class));
    verify(outbox).append(any(UserDataWipeRequested.class));
    assertEquals(AccountCancellationStatus.COMPLETED, application.getStatus());
  }

  @Test
  void shouldCompleteWhenAccountAlreadyMissing() {
    AccountCancellation application = AccountCancellation.request(10L, "user10", "昵称", "13800000000", "a@b.com",
        "token-hash", "web", "1.2.3.4", LocalDateTime.now());
    when(cancellationRepository.findById(99L)).thenReturn(Optional.of(application));
    when(userAccountRepository.findById(10L)).thenReturn(Optional.empty());

    processor.processOne(99L);

    verify(userAccountDataPurger).purge(10L);
    verify(userAccountRepository, never()).hardDelete(10L);
    verify(userAccountRepository, never()).save(any(UserAccount.class));
    assertEquals(AccountCancellationStatus.COMPLETED, application.getStatus());
  }

  private static UserAccount account(Long id) {
    UserAccount account = new UserAccount();
    account.restore(id, "user10", "昵称", "avatar", "hash", "a@b.com", "13800000000", "sig", UserAccountStatus.ACTIVE, 1L,
        SelfDestructPolicy.OFF, null, LocalDateTime.now(), UserAccountRole.USER, 0L, LocalDateTime.now(), 0L,
        LocalDateTime.now());
    return account;
  }
}
