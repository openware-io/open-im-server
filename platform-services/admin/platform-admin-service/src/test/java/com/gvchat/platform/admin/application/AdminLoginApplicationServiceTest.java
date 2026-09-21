package com.gvchat.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.domain.model.SaaAdminAccount;
import com.gvchat.platform.admin.domain.port.SaaAdminAccountRepository;
import com.gvchat.platform.admin.infra.security.SaaAdminSessionStore;
import com.gvchat.platform.admin.infra.security.SessionTtl;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SaaS 后台账号密码登录：查账号 + BCrypt 校验 + 创建 Redis 会话。 */
class AdminLoginApplicationServiceTest {

  /** 与 V2 种子 admin 一致：密码 e8280ac0d25d4bc0a1e1 的 BCrypt 哈希。 */
  private static final String SEED_PASSWORD = "e8280ac0d25d4bc0a1e1";
  private static final String SEED_HASH = "$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS";

  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private SaaAdminAccountRepository accountRepository;
  private SaaAdminSessionStore sessionStore;
  private AdminLoginApplicationService service;

  @BeforeEach
  void setUp() {
    accountRepository = mock(SaaAdminAccountRepository.class);
    sessionStore = mock(SaaAdminSessionStore.class);
    service = new AdminLoginApplicationService(accountRepository, passwordEncoder, sessionStore);
  }

  @Test
  void login_createsSessionOnCorrectPassword() {
    SaaAdminAccount account = new SaaAdminAccount(1L, "admin", SEED_HASH, "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findByUsername("admin")).thenReturn(Optional.of(account));
    when(sessionStore.createSession(any(), any())).thenReturn("session-1");

    AdminAuthResult result = service.login("admin", SEED_PASSWORD, SessionTtl.DEFAULT);

    assertEquals("session-1", result.sessionId());
    assertEquals("admin", result.user().username());
    assertEquals("管理员", result.user().displayName());
    assertEquals("SUPER_ADMIN", result.user().role());
  }

  @Test
  void login_rejectsWrongPassword() {
    SaaAdminAccount account = new SaaAdminAccount(1L, "admin", SEED_HASH, "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findByUsername("admin")).thenReturn(Optional.of(account));

    ApiException ex = assertThrows(ApiException.class, () -> service.login("admin", "wrong-password", SessionTtl.DEFAULT));
    assertEquals(401, ex.getStatus());
    assertEquals("ADMIN_LOGIN_INVALID", ex.getCode());
  }
}
