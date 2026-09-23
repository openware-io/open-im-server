package io.openware.im.user.application.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.im.user.api.admin.AdminUserDeleteResponse;
import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.model.SelfDestructPolicy;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.port.PasswordHasher;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.integration.UnifiedAccountPurgeClient;
import io.openware.im.user.infra.persistence.admin.AdminUserCascadeMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 后台删除用户：守卫（404/409/400）+ 级联计数 + 墓碑化 + 统一账号清理在事务内失败即抛出。 */
class AdminUserDeletionApplicationServiceTest {

  private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
  private final AdminUserCascadeMapper cascadeMapper = mock(AdminUserCascadeMapper.class);
  private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
  private final UserStatusEventOutbox outbox = mock(UserStatusEventOutbox.class);
  private final UnifiedAccountPurgeClient unifiedAccountPurgeClient = mock(UnifiedAccountPurgeClient.class);
  private final AdminUserDeletionApplicationService service = new AdminUserDeletionApplicationService(
      userAccountRepository, cascadeMapper, passwordHasher, outbox, unifiedAccountPurgeClient);

  @Test
  void shouldCascadeDeleteAndTombstoneUsername() {
    UserAccount account = account(71L, "im_71", UserAccountRole.USER);
    when(userAccountRepository.findById(71L)).thenReturn(Optional.of(account));
    when(passwordHasher.hash(any())).thenReturn("random-hash");
    stubCascadeCounts();
    when(unifiedAccountPurgeClient.purgeImAccount("im_71"))
        .thenReturn(new UnifiedAccountPurgeClient.PurgeResult(1, 1, true, "CUSTOMER"));

    AdminUserDeleteResponse response = service.deleteUser(71L, "im_71", 1L);

    assertTrue(response.deleted());
    assertEquals("im_71", response.username());
    assertTrue(response.messagesPreserved());
    assertTrue(response.tombstoneUsername().startsWith("deleted_71_"), response.tombstoneUsername());
    assertEquals(3L, response.cascade().deviceSessions());
    assertEquals(2L, response.cascade().deviceKeys());
    assertEquals(1L, response.cascade().favorites());
    assertEquals(4L, response.cascade().friendRelations());
    assertEquals(5L, response.cascade().groupMembers());
    assertEquals(1L, response.cascade().account());
    // 统一账号模型清理计数（idt_login_identity / idt_oauth_link / 客户孤儿 idt_account）
    assertEquals(1L, response.cascade().loginIdentities());
    assertEquals(1L, response.cascade().oauthLinks());
    assertEquals(1L, response.cascade().unifiedAccounts());
    assertEquals(false, response.cascade().unifiedAccountRetained());
    assertEquals("CUSTOMER", response.cascade().unifiedAccountType());

    // 主记录墓碑化而不是物理删除：客户档案按 username=deleted_<id>_<hash> 判定「IM 账号已删除」。
    verify(userAccountRepository, never()).hardDelete(anyLong());
    verify(userAccountRepository).save(account);
    assertEquals(UserAccountStatus.DISABLED, account.getStatus());
    assertEquals("已注销用户", account.getNickname());
    assertEquals("", account.getPhone());
    assertEquals(response.tombstoneUsername(), account.getUsername());
    // 消息本体一律不删：整个流程不投递「聊天记录清理」事件。
    verify(outbox).append(any(UserAuthenticationInvalidated.class));
    verify(unifiedAccountPurgeClient).purgeImAccount("im_71");
  }

  /** 员工账号本体保留（只解绑 IM 身份）时，计数必须把「保留」讲清楚，供审计区分。 */
  @Test
  void shouldReportUnifiedAccountRetainedForEmployeeAccount() {
    UserAccount account = account(71L, "im_71", UserAccountRole.USER);
    when(userAccountRepository.findById(71L)).thenReturn(Optional.of(account));
    when(passwordHasher.hash(any())).thenReturn("random-hash");
    stubCascadeCounts();
    when(unifiedAccountPurgeClient.purgeImAccount("im_71"))
        .thenReturn(new UnifiedAccountPurgeClient.PurgeResult(1, 1, false, "EMPLOYEE"));

    AdminUserDeleteResponse response = service.deleteUser(71L, "im_71", 1L);

    assertEquals(0L, response.cascade().unifiedAccounts());
    assertEquals(true, response.cascade().unifiedAccountRetained());
    assertEquals("EMPLOYEE", response.cascade().unifiedAccountType());
  }

  @Test
  void shouldPropagateUnifiedAccountPurgeFailureSoTransactionRollsBack() {
    UserAccount account = account(71L, "im_71", UserAccountRole.USER);
    when(userAccountRepository.findById(71L)).thenReturn(Optional.of(account));
    when(passwordHasher.hash(any())).thenReturn("random-hash");
    stubCascadeCounts();
    // 身份域调用失败 → 异常必须冒泡，让本事务整体回滚（不留半删状态）。
    when(unifiedAccountPurgeClient.purgeImAccount("im_71"))
        .thenThrow(new IllegalStateException("清理统一账号落点失败"));

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.deleteUser(71L, "im_71", 1L));

    assertTrue(exception.getMessage().contains("清理统一账号落点失败"));
  }

  @Test
  void shouldRejectMissingAccountWith404() {
    when(userAccountRepository.findById(71L)).thenReturn(Optional.empty());

    ApiException exception = assertThrows(ApiException.class, () -> service.deleteUser(71L, "im_71", 1L));

    assertEquals(404, exception.getStatus());
    assertEquals("USER_NOT_FOUND", exception.getCode());
    verifyNoInteractions(cascadeMapper, unifiedAccountPurgeClient);
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void shouldRejectAdminAccountWith409() {
    when(userAccountRepository.findById(1L)).thenReturn(Optional.of(account(1L, "admin", UserAccountRole.ADMIN)));

    ApiException exception = assertThrows(ApiException.class, () -> service.deleteUser(1L, "admin", 1L));

    assertEquals(409, exception.getStatus());
    assertEquals("ADMIN_ACCOUNT_UNDELETABLE", exception.getCode());
    verifyNoInteractions(cascadeMapper, unifiedAccountPurgeClient);
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void shouldRejectUsernameMismatchWith400() {
    when(userAccountRepository.findById(71L)).thenReturn(Optional.of(account(71L, "im_71", UserAccountRole.USER)));

    ApiException exception = assertThrows(ApiException.class, () -> service.deleteUser(71L, "im_72", 1L));

    assertEquals(400, exception.getStatus());
    assertEquals("USERNAME_CONFIRM_MISMATCH", exception.getCode());
    verifyNoInteractions(cascadeMapper, unifiedAccountPurgeClient);
    verify(userAccountRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void shouldRejectBlankConfirmUsernameWith400() {
    when(userAccountRepository.findById(71L)).thenReturn(Optional.of(account(71L, "im_71", UserAccountRole.USER)));

    ApiException exception = assertThrows(ApiException.class, () -> service.deleteUser(71L, null, 1L));

    assertEquals(400, exception.getStatus());
    assertEquals("USERNAME_CONFIRM_MISMATCH", exception.getCode());
    assertFalse(exception.getMessage().isBlank());
  }

  private void stubCascadeCounts() {
    when(cascadeMapper.deleteDeviceTokens(71L)).thenReturn(6);
    when(cascadeMapper.deleteDeviceSessions(71L)).thenReturn(3);
    when(cascadeMapper.deleteDeviceKeys(71L)).thenReturn(2);
    when(cascadeMapper.deleteNotificationSettings(71L)).thenReturn(1);
    when(cascadeMapper.deletePrivacySettings(71L)).thenReturn(1);
    when(cascadeMapper.deleteSecurityQuestions(71L)).thenReturn(1);
    when(cascadeMapper.deleteFavorites(71L)).thenReturn(1);
    when(cascadeMapper.deleteFriendRelations(71L)).thenReturn(4);
    when(cascadeMapper.deleteFriendRequests(71L)).thenReturn(2);
    when(cascadeMapper.deleteGroupMembers(71L)).thenReturn(5);
    when(cascadeMapper.deleteChannelSubscriptions(71L)).thenReturn(1);
    when(cascadeMapper.deleteSecretChats(71L)).thenReturn(1);
    when(cascadeMapper.deleteSecretGroupMembers(71L)).thenReturn(1);
    when(cascadeMapper.deleteStickers(71L)).thenReturn(2);
    when(cascadeMapper.deleteStickerQuota(71L)).thenReturn(1);
    when(cascadeMapper.deleteStatusOperations(71L)).thenReturn(0);
    when(cascadeMapper.deleteOutboxEvents(71L)).thenReturn(0);
  }

  private static UserAccount account(Long id, String username, UserAccountRole role) {
    UserAccount account = new UserAccount();
    account.restore(id, username, "昵称", "avatar", "hash", "a@b.com", "13800000000", "sig",
        UserAccountStatus.ACTIVE, 1L, SelfDestructPolicy.OFF, null, LocalDateTime.now(), role, 0L,
        LocalDateTime.now(), 0L, LocalDateTime.now());
    return account;
  }
}
