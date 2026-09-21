package com.gvchat.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.domain.model.SaaAdminAccount;
import com.gvchat.platform.admin.domain.port.SaaAdminAccountRepository;
import com.gvchat.platform.admin.infra.IdaasSsoClient;
import com.gvchat.platform.admin.infra.security.SaaAdminSessionStore;
import com.gvchat.platform.admin.infra.security.SessionTtl;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SaaS 后台 SSO 交换：一次性票据 → 按 idaas_subject 映射 → 创建 Redis 会话。 */
class SsoAuthApplicationServiceTest {

  private SaaAdminAccountRepository accountRepository;
  private IdaasSsoClient idaasSsoClient;
  private SaaAdminSessionStore sessionStore;
  private SsoAuthApplicationService service;

  @BeforeEach
  void setUp() {
    accountRepository = mock(SaaAdminAccountRepository.class);
    idaasSsoClient = mock(IdaasSsoClient.class);
    sessionStore = mock(SaaAdminSessionStore.class);
    service = new SsoAuthApplicationService(accountRepository, idaasSsoClient, sessionStore);
  }

  @Test
  void exchange_mapsIdaasSubjectToAdminAccount() {
    when(idaasSsoClient.verifyTicket("ticket-1")).thenReturn(new IdaasSsoClient.SsoUser(1L, "admin", true));
    SaaAdminAccount account = new SaaAdminAccount(1L, "admin", "hash", "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findBySubject("1")).thenReturn(Optional.of(account));
    when(sessionStore.createSession(any(), any())).thenReturn("session-1");

    AdminAuthResult response = service.exchange("ticket-1", SessionTtl.DEFAULT);

    assertEquals("session-1", response.sessionId());
    assertEquals("admin", response.user().username());
    assertEquals("管理员", response.user().displayName());
    assertEquals("SUPER_ADMIN", response.user().role());
  }

  @Test
  void exchange_returnsNotFoundWhenNoMapping() {
    when(idaasSsoClient.verifyTicket("ticket-2")).thenReturn(new IdaasSsoClient.SsoUser(999L, "unknown", true));
    when(accountRepository.findBySubject("999")).thenReturn(Optional.empty());

    ApiException ex = assertThrows(ApiException.class, () -> service.exchange("ticket-2", SessionTtl.DEFAULT));
    assertEquals(404, ex.getStatus());
    assertEquals("SaaS_ADMIN_ACCOUNT_NOT_FOUND", ex.getCode());
  }

  @Test
  void exchange_rejectsInvalidTicket() {
    when(idaasSsoClient.verifyTicket("bad")).thenReturn(new IdaasSsoClient.SsoUser(0L, "", false));

    ApiException ex = assertThrows(ApiException.class, () -> service.exchange("bad", SessionTtl.DEFAULT));
    assertEquals(401, ex.getStatus());
    assertEquals("SSO_TICKET_INVALID", ex.getCode());
  }

  /**
   * 集团统一登录门户选了「30 天」时，本后台会话必须**继承**集团会话的剩余时长，
   * 不能被 SessionTtl.SSO（1 天）截断——曾经就是这样把门户选的 7 天/30 天缩水的。
   */
  @Test
  void exchange_inheritsGroupSessionValidityBeyondOneDay() {
    long groupExpiresAt = System.currentTimeMillis() + Duration.ofDays(30).toMillis();
    when(idaasSsoClient.verifyTicket("ticket-30d"))
        .thenReturn(new IdaasSsoClient.SsoUser(1L, "admin", true, groupExpiresAt));
    SaaAdminAccount account = new SaaAdminAccount(1L, "admin", "hash", "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findBySubject("1")).thenReturn(Optional.of(account));
    when(sessionStore.createSession(any(), any())).thenReturn("session-30d");

    AdminAuthResult response = service.exchange("ticket-30d", SessionTtl.MAX);

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(sessionStore).createSession(any(), ttl.capture());
    long hours = ttl.getValue().toHours();
    assertTrue(hours > 24, "不得被 1 天上限截断，实际 " + hours + " 小时");
    assertTrue(hours >= 719 && hours <= 720, "应继承集团会话剩余时长（≈30 天），实际 " + hours + " 小时");
    assertTrue(response.expiresAt() - System.currentTimeMillis() > Duration.ofDays(29).toMillis());
  }

  /** 集团会话只剩 5 分钟时，本后台会话也只能活 5 分钟（取较小值，不许超过集团会话）。 */
  @Test
  void exchange_neverOutlivesGroupSession() {
    long groupExpiresAt = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
    when(idaasSsoClient.verifyTicket("ticket-5m"))
        .thenReturn(new IdaasSsoClient.SsoUser(1L, "admin", true, groupExpiresAt));
    SaaAdminAccount account = new SaaAdminAccount(1L, "admin", "hash", "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findBySubject("1")).thenReturn(Optional.of(account));
    when(sessionStore.createSession(any(), any())).thenReturn("session-5m");

    service.exchange("ticket-5m", SessionTtl.MAX);

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(sessionStore).createSession(any(), ttl.capture());
    assertTrue(ttl.getValue().toMinutes() <= 5, "不得超过集团会话剩余时长");
  }

  /** 集团会话已过期：拒绝换会话（不得发放新会话）。 */
  @Test
  void exchange_rejectsAlreadyExpiredGroupSession() {
    when(idaasSsoClient.verifyTicket("ticket-expired"))
        .thenReturn(new IdaasSsoClient.SsoUser(1L, "admin", true, System.currentTimeMillis() - 1000));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.exchange("ticket-expired", SessionTtl.MAX));

    assertEquals(401, ex.getStatus());
    assertEquals("SSO_TICKET_EXPIRED", ex.getCode());
  }
}
