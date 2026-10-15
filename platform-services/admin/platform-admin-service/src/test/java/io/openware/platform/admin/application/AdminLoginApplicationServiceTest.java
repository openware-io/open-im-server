package io.openware.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.security.ConsAdminSessionStore;
import io.openware.platform.admin.infra.security.SessionTtl;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SaaS 后台账号密码登录：查账号 + BCrypt 校验 + 创建 Redis 会话。 */
class AdminLoginApplicationServiceTest {

  /**
   * 测试专属口令与哈希：运行时生成，仓库内不保留任何真实种子口令或其哈希。
   * 种子账号的真实口令由部署引导流程设置（见 docs/renovation/ENGINEERING_GOVERNANCE_03_REMEDIATION.md DC-01）。
   */
  private static final String TEST_PASSWORD = "test-only-password";
  private static final String TEST_HASH = new BCryptPasswordEncoder().encode(TEST_PASSWORD);

  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private ConsAdminAccountRepository accountRepository;
  private ConsAdminSessionStore sessionStore;
  private AdminLoginApplicationService service;

  @BeforeEach
  void setUp() {
    accountRepository = mock(ConsAdminAccountRepository.class);
    sessionStore = mock(ConsAdminSessionStore.class);
    service = new AdminLoginApplicationService(accountRepository, passwordEncoder, sessionStore);
  }

  @Test
  void login_createsSessionOnCorrectPassword() {
    ConsAdminAccount account = new ConsAdminAccount(1L, "admin", TEST_HASH, "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findByUsername("admin")).thenReturn(Optional.of(account));
    when(sessionStore.createSession(any(), any())).thenReturn("session-1");

    AdminAuthResult result = service.login("admin", TEST_PASSWORD, SessionTtl.DEFAULT);

    assertEquals("session-1", result.sessionId());
    assertEquals("admin", result.user().username());
    assertEquals("管理员", result.user().displayName());
    assertEquals("SUPER_ADMIN", result.user().role());
  }

  @Test
  void login_rejectsWrongPassword() {
    ConsAdminAccount account = new ConsAdminAccount(1L, "admin", TEST_HASH, "管理员", "1",
        AdminRole.SUPER_ADMIN, "ACTIVE", LocalDateTime.now(), LocalDateTime.now());
    when(accountRepository.findByUsername("admin")).thenReturn(Optional.of(account));

    ApiException ex = assertThrows(ApiException.class, () -> service.login("admin", "wrong-password", SessionTtl.DEFAULT));
    assertEquals(401, ex.getStatus());
    assertEquals("ADMIN_LOGIN_INVALID", ex.getCode());
  }
}
