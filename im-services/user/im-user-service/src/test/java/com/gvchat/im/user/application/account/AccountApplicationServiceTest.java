package com.gvchat.im.user.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.port.TokenProvider;
import com.gvchat.im.user.application.account.command.ChangeUserStatusCommand;
import com.gvchat.im.user.application.account.result.ChangeUserStatusResult;
import com.gvchat.im.user.application.device.DeviceSessionApplicationService;
import com.gvchat.im.user.domain.account.event.UserAuthenticationInvalidated;
import com.gvchat.im.user.domain.account.event.UserStatusChanged;
import com.gvchat.im.user.domain.account.model.SelfDestructPolicy;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountRole;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.model.UserStatusOperation;
import com.gvchat.im.user.domain.account.port.PasswordHasher;
import com.gvchat.im.user.domain.account.port.UserAuthenticationProjectionPort;
import com.gvchat.im.user.domain.account.port.UserStatusEventOutbox;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.account.repository.UserStatusOperationRepository;
import com.gvchat.im.user.infra.security.IdaasSsoClient;
import com.gvchat.im.user.media.MediaReferenceClient;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountApplicationServiceTest {
  private final UserAccountRepository accountRepository = mock(UserAccountRepository.class);
  private final UserStatusOperationRepository operationRepository = mock(UserStatusOperationRepository.class);
  private final UserStatusEventOutbox eventOutbox = mock(UserStatusEventOutbox.class);
  private final AccountApplicationService service = new AccountApplicationService(accountRepository,
      mock(TokenProvider.class), mock(PasswordHasher.class), operationRepository,
      eventOutbox, mock(UserAuthenticationProjectionPort.class), mock(MediaReferenceClient.class), mock(IdaasSsoClient.class),
      mock(DeviceSessionApplicationService.class));

  @Test
  void disableShouldPersistVersionedStatusAuditAndAuthenticationInvalidation() {
    UserAccount account = account(8L, UserAccountStatus.ACTIVE, 3L);
    when(operationRepository.findByIdempotencyKey("status-8-3")).thenReturn(Optional.empty());
    when(accountRepository.findById(8L)).thenReturn(Optional.of(account));
    when(accountRepository.saveStatusIfVersionMatches(account, 3L)).thenReturn(true);

    ChangeUserStatusResult result = service.changeStatus(command(8L, UserAccountStatus.DISABLED, 3L));

    assertThat(result.status()).isEqualTo(UserAccountStatus.DISABLED);
    assertThat(result.statusVersion()).isEqualTo(4L);
    verify(operationRepository).save(any(UserStatusOperation.class), eq(3L), eq(99L), eq("违反社区规范"));
    ArgumentCaptor<UserStatusChanged> statusEvent = ArgumentCaptor.forClass(UserStatusChanged.class);
    verify(eventOutbox).append(statusEvent.capture());
    assertThat(statusEvent.getValue().statusVersion()).isEqualTo(4L);
    verify(eventOutbox).append(any(UserAuthenticationInvalidated.class));
  }

  @Test
  void sameIdempotencyKeyShouldReturnStoredResultWithoutWritingAgain() {
    UserStatusOperation stored = new UserStatusOperation(1, 8L, UserAccountStatus.ACTIVE,
        UserAccountStatus.DISABLED, 4L, "status-8-3", "correlation-8", LocalDateTime.now());
    when(operationRepository.findByIdempotencyKey("status-8-3")).thenReturn(Optional.of(stored));

    ChangeUserStatusResult result = service.changeStatus(command(8L, UserAccountStatus.DISABLED, 3L));

    assertThat(result.userId()).isEqualTo(stored.userId());
    assertThat(result.statusVersion()).isEqualTo(stored.statusVersion());
    verify(operationRepository).findByIdempotencyKey("status-8-3");
  }

  private static ChangeUserStatusCommand command(Long userId, UserAccountStatus status, long expectedVersion) {
    return new ChangeUserStatusCommand(1, userId, status, expectedVersion, "status-8-3", 99L, "违反社区规范",
        "correlation-8");
  }

  private static UserAccount account(Long id, UserAccountStatus status, long statusVersion) {
    UserAccount account = new UserAccount();
    account.restore(id, "member", "member", "", "hash", "", "", "", status, statusVersion,
        SelfDestructPolicy.OFF, null, null, UserAccountRole.USER, 1L, LocalDateTime.now(), 1L, LocalDateTime.now());
    return account;
  }
}
