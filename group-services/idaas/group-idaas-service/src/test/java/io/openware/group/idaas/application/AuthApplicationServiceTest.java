package io.openware.group.idaas.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.group.idaas.api.dto.AuthDtos.LoginRequest;
import io.openware.group.idaas.api.dto.AuthDtos.LoginResponse;
import io.openware.group.idaas.infra.persistence.mapper.GroupAccountMapper;
import io.openware.group.idaas.infra.persistence.po.GroupAccountPo;
import io.openware.group.idaas.infra.security.GroupSessionTtl;
import io.openware.group.idaas.infra.security.IdaasSessionStore;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证域登录：密码正确创建 Redis 会话，密码错误拒绝。
 */
class AuthApplicationServiceTest {

  private GroupAccountMapper accountMapper;
  private PasswordEncoder passwordEncoder;
  private IdaasSessionStore sessionStore;
  private AuthApplicationService service;

  @BeforeEach
  void setUp() {
    accountMapper = mock(GroupAccountMapper.class);
    passwordEncoder = new BCryptPasswordEncoder();
    sessionStore = mock(IdaasSessionStore.class);
    service = new AuthApplicationService(accountMapper, passwordEncoder, sessionStore);
  }

  @Test
  void login_createsSessionWhenPasswordMatches() {
    GroupAccountPo account = new GroupAccountPo();
    account.setId(1L);
    account.setUsername("admin");
    account.setPassword(passwordEncoder.encode("admin123"));
    account.setStatus("ENABLED");
    when(accountMapper.selectOne(any())).thenReturn(account);
    when(sessionStore.createSession(1L, "admin")).thenReturn("session-abc");

    LoginResponse response = service.login(new LoginRequest("admin", "admin123"));

    assertNotNull(response.sessionId());
    assertEquals("session-abc", response.sessionId());
    assertTrue(response.expiresAt() > System.currentTimeMillis());
    verify(sessionStore).createSession(1L, "admin");
  }

  @Test
  void login_rejectsWhenPasswordMismatch() {
    GroupAccountPo account = new GroupAccountPo();
    account.setId(1L);
    account.setUsername("admin");
    account.setPassword(passwordEncoder.encode("admin123"));
    account.setStatus("ENABLED");
    when(accountMapper.selectOne(any())).thenReturn(account);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.login(new LoginRequest("admin", "wrong-password")));

    assertEquals("AUTH_INVALID_CREDENTIAL", ex.getCode());
    assertEquals(401, ex.getStatus());
  }

  /** 门户「登录有效时长」：白名单档位必须真的落到会话 TTL 上（30 天 = 720 小时）。 */
  @Test
  void login_honoursWhitelistedTtlHours() {
    GroupAccountPo account = enabledAccount();
    when(accountMapper.selectOne(any())).thenReturn(account);
    when(sessionStore.createSession(any(Long.class), any(String.class), any(Duration.class))).thenReturn("session-30d");
    when(sessionStore.sessionExpiresAt("session-30d"))
        .thenReturn(System.currentTimeMillis() + Duration.ofHours(720).toMillis());

    LoginResponse response = service.login(new LoginRequest("admin", "admin123", 720));

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(sessionStore).createSession(any(Long.class), any(String.class), ttl.capture());
    assertEquals(720, ttl.getValue().toHours());
    assertEquals(GroupSessionTtl.MAX, ttl.getValue());
    // 响应里的到期时间要能反映 30 天，门户据此展示「有效期至 …」
    assertTrue(response.expiresAt() - System.currentTimeMillis() > Duration.ofDays(29).toMillis());
  }

  /** 非白名单值（例如客户端伪造 9999 小时）回退默认 24 小时，不得放行超长会话。 */
  @Test
  void login_fallsBackToDefaultForNonWhitelistedTtlHours() {
    GroupAccountPo account = enabledAccount();
    when(accountMapper.selectOne(any())).thenReturn(account);
    when(sessionStore.createSession(1L, "admin")).thenReturn("session-default");

    LoginResponse response = service.login(new LoginRequest("admin", "admin123", 9999));

    assertEquals("session-default", response.sessionId());
    // 24 小时走「默认 TTL」这条调用（与 GroupSessionTtl.DEFAULT 相等时不显式传 TTL）
    verify(sessionStore).createSession(1L, "admin");
    verify(sessionStore, never()).createSession(any(Long.class), any(String.class), any(Duration.class));
  }

  private GroupAccountPo enabledAccount() {
    GroupAccountPo account = new GroupAccountPo();
    account.setId(1L);
    account.setUsername("admin");
    account.setPassword(passwordEncoder.encode("admin123"));
    account.setStatus("ENABLED");
    return account;
  }
}
