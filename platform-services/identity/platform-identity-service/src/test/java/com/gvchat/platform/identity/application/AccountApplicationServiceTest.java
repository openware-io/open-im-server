package com.gvchat.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.port.TokenProvider;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import com.gvchat.platform.identity.infra.persistence.po.IdentityAccountPo;
import com.gvchat.platform.identity.infra.persistence.po.LoginIdentityPo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SaaS 账号注册/登录：注册写账号 + 登录标识，登录校验凭证后返回带 JWT 的登录结果。 */
class AccountApplicationServiceTest {

  private final IdentityAccountMapper accountMapper = mock(IdentityAccountMapper.class);
  private final LoginIdentityMapper loginIdentityMapper = mock(LoginIdentityMapper.class);
  private final OauthLinkMapper oauthLinkMapper = mock(OauthLinkMapper.class);
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final TokenProvider tokenProvider = mock(TokenProvider.class);
  private final JwtProperties jwtProperties = new JwtProperties();
  private final AccountApplicationService service =
      new AccountApplicationService(accountMapper, loginIdentityMapper, oauthLinkMapper, passwordEncoder, tokenProvider, jwtProperties);

  @Test
  void register_createsActiveAccountAndLoginIdentity() {
    IdentityAccountPo account = service.register("phone", "13800000001", "pass1234");

    assertEquals("ACTIVE", account.getStatus());
    assertEquals("CUSTOMER", account.getAccountType());
    assertNotNull(account.getCreatedAt());
    assertNotNull(account.getUpdatedAt());
    verify(accountMapper).insert(account);

    ArgumentCaptor<LoginIdentityPo> captor = ArgumentCaptor.forClass(LoginIdentityPo.class);
    verify(loginIdentityMapper).insert(captor.capture());
    assertEquals("phone", captor.getValue().getLoginType());
    assertEquals("13800000001", captor.getValue().getLoginIdentifier());
    assertTrue(passwordEncoder.matches("pass1234", captor.getValue().getCredential()));
    assertEquals("ACTIVE", captor.getValue().getStatus());
  }

  @Test
  void login_returnsJwtTokenWhenCredentialMatches() {
    LoginIdentityPo li = new LoginIdentityPo();
    li.setAccountId(7L);
    li.setCredential(passwordEncoder.encode("pass1234"));
    when(loginIdentityMapper.selectOne(any())).thenReturn(li);
    IdentityAccountPo account = new IdentityAccountPo();
    account.setId(7L);
    when(accountMapper.selectById(7L)).thenReturn(account);
    when(tokenProvider.createAccessToken(7L, "13800000001", 0)).thenReturn("test-jwt");

    AccountApplicationService.LoginResult result = service.login("phone", "13800000001", "pass1234");

    assertEquals(7L, result.accountId());
    assertEquals("test-jwt", result.accessToken());
    assertEquals(604800L, result.expiresInSeconds());
    verify(accountMapper).selectById(7L);
  }

  @Test
  void login_throwsWhenCredentialMismatch() {
    LoginIdentityPo li = new LoginIdentityPo();
    li.setAccountId(7L);
    li.setCredential(passwordEncoder.encode("wrong"));
    when(loginIdentityMapper.selectOne(any())).thenReturn(li);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.login("phone", "13800000001", "pass1234"));

    assertEquals(401, ex.getStatus());
    assertEquals("AUTH_INVALID_CREDENTIAL", ex.getCode());
    assertEquals("用户名或密码错误", ex.getMessage());
  }

  @Test
  void login_throwsWhenIdentityMissing() {
    when(loginIdentityMapper.selectOne(any())).thenReturn(null);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.login("phone", "13800000001", "pass1234"));

    assertEquals(401, ex.getStatus());
    assertEquals("AUTH_INVALID_CREDENTIAL", ex.getCode());
  }

  @Test
  void ensureImReusesAccountCreatedByOAuth() {
    var link = new com.gvchat.platform.identity.infra.persistence.po.OauthLinkPo();
    link.setAccountId(71L);
    link.setStatus("ACTIVE");
    when(oauthLinkMapper.selectOne(any())).thenReturn(link);
    var account = new IdentityAccountPo();
    account.setId(71L);
    account.setStatus("ACTIVE");
    account.setAccountType("CUSTOMER");
    when(accountMapper.selectById(71L)).thenReturn(account);
    assertEquals(71L, service.ensureAccount("IM", "im_71", "EMPLOYEE").getId());
    assertEquals("EMPLOYEE", account.getAccountType());
    org.mockito.Mockito.verify(accountMapper, org.mockito.Mockito.never()).insert(any(IdentityAccountPo.class));
  }
}
