package io.openware.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.port.TokenProvider;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.platform.identity.domain.account.model.IdentityAccount;
import io.openware.platform.identity.domain.account.model.LoginIdentity;
import io.openware.platform.identity.domain.account.model.OauthLink;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import io.openware.platform.identity.domain.account.repository.LoginIdentityRepository;
import io.openware.platform.identity.domain.account.repository.OauthLinkRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SaaS 账号注册/登录：注册写账号 + 登录标识，登录校验凭证后返回带 JWT 的登录结果。
 *
 * <p>测试对象是应用服务，因此替身是**领域端口**：此前它替身的是 MyBatis mapper 与 PO，使测试与持久化
 * 实现绑定；改为端口后，测试只依赖领域契约（standards/10“测试规则”）。
 */
class AccountApplicationServiceTest {

  private final IdentityAccountRepository accountRepository = mock(IdentityAccountRepository.class);
  private final LoginIdentityRepository loginIdentityRepository = mock(LoginIdentityRepository.class);
  private final OauthLinkRepository oauthLinkRepository = mock(OauthLinkRepository.class);
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final TokenProvider tokenProvider = mock(TokenProvider.class);
  private final JwtProperties jwtProperties = new JwtProperties();
  private final AccountApplicationService service =
      new AccountApplicationService(accountRepository, loginIdentityRepository, oauthLinkRepository,
          passwordEncoder, tokenProvider, jwtProperties);

  /** 端口保存账号时返回带标识的实例（真实适配器在 insert 后回填主键，测试替身等价模拟）。 */
  private void stubAccountSaveReturningId(long id) {
    when(accountRepository.save(any(IdentityAccount.class))).thenAnswer(invocation -> {
      IdentityAccount saved = invocation.getArgument(0);
      return new IdentityAccount(id, saved.status(), saved.accountType());
    });
  }

  @Test
  void register_createsActiveAccountAndLoginIdentity() {
    stubAccountSaveReturningId(11L);

    IdentityAccount account = service.register("phone", "13800000001", "pass1234");

    // 状态与类型由领域工厂固定，测试断言的是领域不变量而非 PO 字段。
    assertEquals(IdentityAccount.STATUS_ACTIVE, account.status());
    assertEquals(IdentityAccount.TYPE_CUSTOMER, account.accountType());
    assertNotNull(account.id());

    ArgumentCaptor<LoginIdentity> captor = ArgumentCaptor.forClass(LoginIdentity.class);
    verify(loginIdentityRepository).save(captor.capture());
    assertEquals("phone", captor.getValue().loginType());
    assertEquals("13800000001", captor.getValue().loginIdentifier());
    assertTrue(passwordEncoder.matches("pass1234", captor.getValue().credential()));
    assertEquals(LoginIdentity.STATUS_ACTIVE, captor.getValue().status());
    assertEquals(11L, captor.getValue().accountId());
  }

  @Test
  void login_returnsJwtTokenWhenCredentialMatches() {
    when(loginIdentityRepository.findActiveByLoginIdentifier("phone", "13800000001"))
        .thenReturn(Optional.of(new LoginIdentity(5L, 7L, "phone", "13800000001",
            passwordEncoder.encode("pass1234"), LoginIdentity.STATUS_ACTIVE)));
    when(accountRepository.findById(7L))
        .thenReturn(Optional.of(new IdentityAccount(7L, IdentityAccount.STATUS_ACTIVE,
            IdentityAccount.TYPE_CUSTOMER)));
    when(tokenProvider.createAccessToken(7L, "13800000001", 0)).thenReturn("test-jwt");

    AccountApplicationService.LoginResult result = service.login("phone", "13800000001", "pass1234");

    assertEquals(7L, result.accountId());
    assertEquals("test-jwt", result.accessToken());
    assertEquals(604800L, result.expiresInSeconds());
    // 登录只应走"可用登录标识"这一条查询：状态过滤收在端口方法里。
    verify(loginIdentityRepository).findActiveByLoginIdentifier("phone", "13800000001");
  }

  @Test
  void login_throwsWhenCredentialMismatch() {
    when(loginIdentityRepository.findActiveByLoginIdentifier("phone", "13800000001"))
        .thenReturn(Optional.of(new LoginIdentity(5L, 7L, "phone", "13800000001",
            passwordEncoder.encode("wrong"), LoginIdentity.STATUS_ACTIVE)));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.login("phone", "13800000001", "pass1234"));

    assertEquals(401, ex.getStatus());
    assertEquals("AUTH_INVALID_CREDENTIAL", ex.getCode());
    assertEquals("用户名或密码错误", ex.getMessage());
  }

  @Test
  void login_throwsWhenIdentityMissing() {
    when(loginIdentityRepository.findActiveByLoginIdentifier(any(), any())).thenReturn(Optional.empty());

    ApiException ex = assertThrows(ApiException.class,
        () -> service.login("phone", "13800000001", "pass1234"));

    assertEquals(401, ex.getStatus());
    assertEquals("AUTH_INVALID_CREDENTIAL", ex.getCode());
  }

  @Test
  void ensureImReusesAccountCreatedByOAuth() {
    when(oauthLinkRepository.findByProviderAccount("IM", "im_71"))
        .thenReturn(Optional.of(OauthLink.bind(71L, "IM", "im_71", null)));
    when(loginIdentityRepository.findUniqueByLoginIdentifier("IM", "im_71")).thenReturn(Optional.empty());
    when(loginIdentityRepository.save(any(LoginIdentity.class))).thenAnswer(i -> i.getArgument(0));
    when(accountRepository.findById(71L)).thenReturn(Optional.of(new IdentityAccount(71L,
        IdentityAccount.STATUS_ACTIVE, IdentityAccount.TYPE_CUSTOMER)));
    when(accountRepository.save(any(IdentityAccount.class))).thenAnswer(i -> i.getArgument(0));

    IdentityAccount ensured = service.ensureAccount("IM", "im_71", "EMPLOYEE");

    // 复用 OAuth 绑定已落到的账号，并按入参修正账号类型（领域方法负责迁移，不整体设值）。
    assertEquals(71L, ensured.id());
    assertEquals("EMPLOYEE", ensured.accountType());
    verify(accountRepository).findById(71L);
    verify(accountRepository).save(any(IdentityAccount.class));
  }
}
