package com.gvchat.im.user.application.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.im.user.domain.account.event.UserAuthenticationInvalidated;
import com.gvchat.im.user.domain.account.model.SelfDestructPolicy;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountRole;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.model.UserSecurityQuestion;
import com.gvchat.im.user.domain.account.port.PasswordHasher;
import com.gvchat.im.user.domain.account.port.PasswordResetMailSender;
import com.gvchat.im.user.domain.account.port.PasswordResetSmsSender;
import com.gvchat.im.user.domain.account.port.PasswordResetTokenStore;
import com.gvchat.im.user.domain.account.port.UserAuthenticationProjectionPort;
import com.gvchat.im.user.domain.account.port.UserStatusEventOutbox;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.account.repository.UserSecurityQuestionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PasswordRecoveryApplicationServiceTest {
  private final UserAccountRepository accountRepository = mock(UserAccountRepository.class);
  private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
  private final PasswordResetTokenStore tokenStore = mock(PasswordResetTokenStore.class);
  private final PasswordResetMailSender mailSender = mock(PasswordResetMailSender.class);
  private final PasswordResetSmsSender smsSender = mock(PasswordResetSmsSender.class);
  private final UserSecurityQuestionRepository securityQuestionRepository = mock(UserSecurityQuestionRepository.class);
  private final UserAuthenticationProjectionPort projection = mock(UserAuthenticationProjectionPort.class);
  private final UserStatusEventOutbox outbox = mock(UserStatusEventOutbox.class);
  private final PasswordRecoveryApplicationService service = new PasswordRecoveryApplicationService(
      accountRepository, passwordHasher, tokenStore, mailSender, smsSender, securityQuestionRepository, projection,
      outbox);

  @Test
  void requestResetShouldIssueTokenAndSendMailForExistingEmail() {
    UserAccount account = account(7L);
    when(accountRepository.findByEmail("a@b.com")).thenReturn(Optional.of(account));

    service.requestReset("A@B.com");

    verify(tokenStore).put(anyString(), eq(7L), any());
    verify(mailSender).send(eq("a@b.com"), contains("/reset-password?token="));
  }

  @Test
  void requestResetShouldNotLeakUnknownEmail() {
    when(accountRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());

    service.requestReset("a@b.com");

    verify(tokenStore, never()).put(anyString(), any(long.class), any());
    verify(mailSender, never()).send(anyString(), anyString());
  }

  @Test
  void resetPasswordShouldChangePasswordInvalidateAuthAndConsumeToken() {
    UserAccount account = account(7L);
    when(tokenStore.findUserId("token-1")).thenReturn(Optional.of(7L));
    when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
    when(passwordHasher.hash("new-pass")).thenReturn("hashed");

    service.resetPassword("token-1", "new-pass");

    verify(accountRepository).save(account);
    verify(projection).save(any());
    verify(outbox).append(any(UserAuthenticationInvalidated.class));
    verify(tokenStore).remove("token-1");
  }

  @Test
  void resetPasswordWithUnknownTokenShouldReject() {
    when(tokenStore.findUserId("bad")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resetPassword("bad", "new-pass"))
        .isInstanceOf(ApiException.class);
    verify(accountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void requestResetBySmsShouldIssueCodeAndSendSmsForExistingPhone() {
    UserAccount account = account(7L);
    when(accountRepository.findByPhone("13800000000")).thenReturn(Optional.of(account));

    service.requestResetBySms("13800000000");

    verify(tokenStore).put(anyString(), eq(7L), any());
    verify(smsSender).sendVerificationCode(eq("13800000000"), anyString());
  }

  @Test
  void resetPasswordBySmsShouldConsumeCodeAndResetPassword() {
    UserAccount account = account(7L);
    when(tokenStore.findUserId("123456")).thenReturn(Optional.of(7L));
    when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
    when(passwordHasher.hash("new-pass")).thenReturn("hashed");

    service.resetPasswordBySms("13800000000", "123456", "new-pass");

    verify(accountRepository).save(account);
    verify(outbox).append(any(UserAuthenticationInvalidated.class));
    verify(tokenStore).remove("123456");
  }

  @Test
  void setSecurityQuestionShouldHashAnswerAndSave() {
    UserSecurityQuestion existing = UserSecurityQuestion.set(7L, "旧问题", "old-hash", LocalDateTime.now());
    when(securityQuestionRepository.findByUserId(7L)).thenReturn(Optional.of(existing));
    when(passwordHasher.hash("答案")).thenReturn("answer-hash");

    service.setSecurityQuestion(7L, " 新问题 ", " 答案 ");

    verify(securityQuestionRepository).save(existing);
  }

  @Test
  void resetPasswordBySecurityQuestionShouldResetWhenAnswerMatches() {
    UserAccount account = account(7L);
    UserSecurityQuestion stored = UserSecurityQuestion.set(7L, "小学班主任", "answer-hash", LocalDateTime.now());
    when(accountRepository.findByUsername("user7")).thenReturn(Optional.of(account));
    when(securityQuestionRepository.findByUserId(7L)).thenReturn(Optional.of(stored));
    when(passwordHasher.matches("王老师", "answer-hash")).thenReturn(true);
    when(passwordHasher.hash("new-pass")).thenReturn("hashed");

    service.resetPasswordBySecurityQuestion("user7", "小学班主任", "王老师", "new-pass");

    verify(accountRepository).save(account);
    verify(outbox).append(any(UserAuthenticationInvalidated.class));
  }

  @Test
  void resetPasswordBySecurityQuestionShouldRejectWhenAnswerMismatch() {
    UserAccount account = account(7L);
    UserSecurityQuestion stored = UserSecurityQuestion.set(7L, "小学班主任", "answer-hash", LocalDateTime.now());
    when(accountRepository.findByUsername("user7")).thenReturn(Optional.of(account));
    when(securityQuestionRepository.findByUserId(7L)).thenReturn(Optional.of(stored));
    when(passwordHasher.matches("错误答案", "answer-hash")).thenReturn(false);

    assertThatThrownBy(() -> service.resetPasswordBySecurityQuestion("user7", "小学班主任", "错误答案", "new-pass"))
        .isInstanceOf(ApiException.class);
    verify(accountRepository, never()).save(any(UserAccount.class));
  }

  private static UserAccount account(Long id) {
    UserAccount account = new UserAccount();
    account.restore(id, "user" + id, "user" + id, "", "hash", "a@b.com", "13800000000", "", UserAccountStatus.ACTIVE, 1L,
        SelfDestructPolicy.OFF, null, LocalDateTime.now(), UserAccountRole.USER, 0L, LocalDateTime.now(), 0L,
        LocalDateTime.now());
    return account;
  }
}
